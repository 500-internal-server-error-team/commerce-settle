package com.commercesettle.settlement.infrastructure.batch;

import com.commercesettle.settlement.application.service.SellerSettlementService;
import com.commercesettle.settlement.domain.exception.PayoutNotAllowedException;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 정산 일배치 Job. item은 판매자 한 명이고 chunk 크기는 1이라, 판매자 한 명의 대상·합계·지급·작업이 한 트랜잭션에 커밋된다(ADR 0007 D4 제안안).
 * reader는 마감 전 지급 대상이 있는 판매자를 seller_id 순 keyset으로 읽는다. 커밋한 판매자의 항목은 선점돼 조회 조건에서 빠지므로, 재시작 위치는 실행
 * 문맥에 저장하지 않고 업무 DB를 다시 조회해 정한다.
 */
@Configuration
class DailySettlementJobConfiguration {
  static final String JOB_NAME = "dailySettlementJob";
  static final String BATCH_DATE = "batchDate";

  private static final Logger LOGGER =
      LoggerFactory.getLogger(DailySettlementJobConfiguration.class);

  @Bean
  Job dailySettlementJob(JobRepository jobRepository, Step settleSellersStep) {
    return new JobBuilder(JOB_NAME, jobRepository).start(settleSellersStep).build();
  }

  /**
   * 지급 전 검증 실패와 항목 잠금 대기 초과는 그 판매자만 건너뛴다. 롤백된 항목은 PAYABLE로 남아 다음 실행에서 다시 고려한다. 그 밖의 오류는 Step을 실패시켜
   * 재시작으로 이어서 처리한다.
   */
  @Bean
  Step settleSellersStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      JdbcPagingItemReader<UUID> payableSellerReader,
      ItemWriter<UUID> sellerSettlementWriter) {
    return new StepBuilder("settleSellers", jobRepository)
        .<UUID, UUID>chunk(1, transactionManager)
        .reader(payableSellerReader)
        .writer(sellerSettlementWriter)
        .faultTolerant()
        .skip(PayoutNotAllowedException.class)
        .skip(PessimisticLockingFailureException.class)
        .skipLimit(Integer.MAX_VALUE)
        .listener(skippedSellerLogger())
        .build();
  }

  @Bean
  @StepScope
  JdbcPagingItemReader<UUID> payableSellerReader(
      DataSource dataSource, @Value("#{jobParameters['batchDate']}") LocalDate batchDate) {
    PostgresPagingQueryProvider query = new PostgresPagingQueryProvider();
    query.setSelectClause("seller_id");
    query.setFromClause("settlement.settlement_items");
    query.setWhereClause("status = 'PAYABLE' AND payout_id IS NULL AND ready_at < :cutoffAt");
    query.setGroupClause("seller_id");
    query.setSortKeys(Map.of("seller_id", Order.ASCENDING));
    return new JdbcPagingItemReaderBuilder<UUID>()
        .name("payableSellerReader")
        .dataSource(dataSource)
        .queryProvider(query)
        .parameterValues(
            Map.of("cutoffAt", SettlementBatch.cutoffOf(batchDate).atOffset(ZoneOffset.UTC)))
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
      @Value("#{jobParameters['batchDate']}") LocalDate batchDate) {
    return chunk -> chunk.forEach(sellerId -> sellers.settle(sellerId, batchDate));
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
