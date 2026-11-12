package com.commercesettle.settlement.infrastructure.batch;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.settlement.application.exception.FixtureBatchConflictException;
import com.commercesettle.settlement.application.exception.ProductSettlementContextUnavailableException;
import com.commercesettle.settlement.application.service.SellerSettlementService;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.infrastructure.observability.DailySettlementProgress;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * 정산 일배치 Job. item은 판매자 한 명이고 chunk 크기는 1이며, 판매자 한 명의 대상·합계·지급·작업은 {@link SellerSettlementService}의
 * 판매자 단위 트랜잭션에 커밋된다. chunk는 DB 트랜잭션을 열지 않아 커머스 지급 컨텍스트를 기다리는 동안 DB 연결을 쥐지 않는다. reader는 마감 전 지급 대상이
 * 있는 판매자를 seller_id 순 keyset으로 읽는다. 커밋한 판매자의 항목은 선점돼 조회 조건에서 빠지므로, 재시작 위치는 실행 문맥에 저장하지 않고 업무 DB를 다시
 * 조회해 정한다.
 */
@Configuration
class DailySettlementJobConfiguration {
  static final String JOB_NAME = "dailySettlementJob";
  static final String BATCH_DATE = "batchDate";

  private static final Logger LOGGER =
      LoggerFactory.getLogger(DailySettlementJobConfiguration.class);

  @Bean
  Job dailySettlementJob(
      JobRepository jobRepository, Step settleSellersStep, DailySettlementProgress progress) {
    return new JobBuilder(JOB_NAME, jobRepository)
        .listener(progress)
        .start(settleSellersStep)
        .build();
  }

  @Bean
  DailySettlementProgress dailySettlementProgress(ObjectProvider<MeterRegistry> meters) {
    return new DailySettlementProgress(meters.getIfAvailable(), JOB_NAME, "settleSellers");
  }

  /**
   * 판매자 한 명의 처리 오류는 그 판매자만 건너뛴다. 매번 같은 오류가 나는 판매자가 뒤 판매자를 막지 않게 한다. 롤백된 항목은 PAYABLE로 남아 다음 실행에서 다시
   * 고려한다. DB 자원·일시 오류는 모든 판매자에게 같이 일어나므로 Step을 실패시켜 복구 뒤 재시작으로 이어서 처리한다. 항목 잠금 대기 초과는 일시 오류지만 그
   * 판매자만의 경합이라 건너뛴다. 커머스 지급 컨텍스트 조회 실패도 모든 판매자에게 같이 일어나므로 Step을 실패시킨다. 지급 전 검증 거절은 그 판매자만의 상태라
   * 건너뛴다.
   */
  @Bean
  Step settleSellersStep(
      JobRepository jobRepository,
      JdbcPagingItemReader<UUID> payableSellerReader,
      ItemWriter<UUID> sellerSettlementWriter,
      DailySettlementProgress progress) {
    return new StepBuilder("settleSellers", jobRepository)
        .<UUID, UUID>chunk(1, new ResourcelessTransactionManager())
        .reader(payableSellerReader)
        .writer(sellerSettlementWriter)
        .faultTolerant()
        .skip(Exception.class)
        .noSkip(FixtureBatchConflictException.class)
        .noSkip(ProductSettlementContextUnavailableException.class)
        .skip(PessimisticLockingFailureException.class)
        .noSkip(TransientDataAccessException.class)
        .noSkip(NonTransientDataAccessResourceException.class)
        .noSkip(RecoverableDataAccessException.class)
        .skipLimit(Integer.MAX_VALUE)
        .listener(skippedSellerLogger())
        .listener(skipSummary())
        .listener((StepExecutionListener) progress)
        .listener((ChunkListener) progress)
        .build();
  }

  @Bean
  @StepScope
  JdbcPagingItemReader<UUID> payableSellerReader(
      DataSource dataSource,
      @Value("#{jobParameters['batchDate']}") LocalDate batchDate,
      @Value("#{jobParameters['fixtureItemIds']}") String fixtureItemIds) {
    PostgresPagingQueryProvider query = new PostgresPagingQueryProvider();
    query.setSelectClause("seller_id");
    query.setFromClause("settlement.settlement_items");
    Set<UUID> selected = selectedItems(fixtureItemIds);
    query.setWhereClause(
        "status = 'PAYABLE' AND payout_id IS NULL AND ready_at < :cutoffAt"
            + (selected == null ? "" : " AND id IN (:fixtureItemIds)"));
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("cutoffAt", SettlementBatch.cutoffOf(batchDate).atOffset(ZoneOffset.UTC));
    if (selected != null) parameters.put("fixtureItemIds", selected);
    query.setGroupClause("seller_id");
    query.setSortKeys(Map.of("seller_id", Order.ASCENDING));
    return new JdbcPagingItemReaderBuilder<UUID>()
        .name("payableSellerReader")
        .dataSource(dataSource)
        .queryProvider(query)
        .parameterValues(parameters)
        .rowMapper((rs, row) -> rs.getObject("seller_id", UUID.class))
        .pageSize(100)
        // 저장한 읽은 건수로 건너뛰면 이미 빠진 판매자 수만큼 남은 판매자까지 건너뛴다.
        .saveState(false)
        .build();
  }

  @Bean
  @StepScope
  ItemWriter<UUID> sellerSettlementWriter(
      SellerSettlementService sellers,
      @Value("#{jobParameters['batchDate']}") LocalDate batchDate,
      @Value("#{jobParameters['fixtureItemIds']}") String fixtureItemIds,
      ObjectProvider<AsyncObservation> observations) {
    Set<UUID> selected = selectedItems(fixtureItemIds);
    AsyncObservation observation = observations.getIfAvailable(() -> AsyncObservation.NOOP);
    return chunk ->
        chunk.forEach(
            sellerId -> {
              observation.run(
                  "settlement.batch.seller",
                  observation.capture(),
                  Map.of("seller.id", sellerId.toString()),
                  null,
                  () -> {
                    if (selected == null) sellers.settle(sellerId, batchDate);
                    else sellers.settleFixture(sellerId, batchDate, selected);
                  });
            });
  }

  private static Set<UUID> selectedItems(String ids) {
    return ids == null
        ? null
        : Arrays.stream(ids.split(","))
            .map(UUID::fromString)
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * 건너뛴 판매자가 있으면 종료 코드를 COMPLETED_WITH_SKIPS로 바꾸고 건너뛴 수를 남긴다. 실행 결과(BatchStatus)는 COMPLETED 그대로다.
   */
  private static StepExecutionListener skipSummary() {
    return new StepExecutionListener() {
      @Override
      public ExitStatus afterStep(StepExecution stepExecution) {
        long skipped = stepExecution.getWriteSkipCount();
        if (skipped == 0
            || !ExitStatus.COMPLETED
                .getExitCode()
                .equals(stepExecution.getExitStatus().getExitCode())) {
          return null;
        }
        LOGGER
            .atWarn()
            .addKeyValue("event", "settlement.daily-batch")
            .addKeyValue("result", "COMPLETED_WITH_SKIPS")
            .addKeyValue("skippedSellers", skipped)
            .log("Daily settlement completed with skipped sellers");
        return new ExitStatus("COMPLETED_WITH_SKIPS");
      }
    };
  }

  private static SkipListener<UUID, UUID> skippedSellerLogger() {
    return new SkipListener<>() {
      @Override
      public void onSkipInWrite(UUID sellerId, Throwable t) {
        LOGGER
            .atWarn()
            .addKeyValue("event", "settlement.daily-batch")
            .addKeyValue("result", "SKIPPED")
            .addKeyValue("sellerId", sellerId)
            .addKeyValue("errorType", t.getClass().getName())
            .log("Seller was skipped in daily settlement");
      }
    };
  }
}
