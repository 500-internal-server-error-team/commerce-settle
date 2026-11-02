package com.commercesettle.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.settlement.application.service.SellerSettlementService;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 강제 종료로 실행 중 상태에 남은 일배치를 실제 PostgreSQL에서 재현한다. 죽은 실행자의 Job·Step 실행 기록을 직접 만들고, 그 실행자가 커밋한 판매자는 업무
 * DB에 미리 처리해 둔다.
 */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Testcontainers
class DailySettlementRecoveryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final String DEAD_EXECUTOR = "settlement-dead";

  @Autowired DailySettlementLauncher launcher;
  @Autowired DailySettlementRecovery recovery;
  @Autowired JobRepository jobRepository;
  @Autowired SellerSettlementService sellers;
  @Autowired SettlementItemRepository items;
  @Autowired JdbcTemplate jdbc;
  @Autowired TaskScheduler taskScheduler;
  @Autowired Clock clock;
  @Autowired ApplicationContext context;

  @Test
  @DisplayName("정체된 잔류 실행은 FAILED로 정리하고 재시작해 커밋하지 않은 판매자만 처리한다")
  void recoversStaleExecutionAndProcessesOnlyUncommittedSellers() throws Exception {
    LocalDate batchDate = LocalDate.of(2026, 8, 1);
    List<UUID> sellerIds = interruptedAfterFirstSeller(batchDate);
    JobExecution residual = residual(batchDate, Duration.ofHours(1));

    assertThat(launcher.run(batchDate)).isEqualTo(BatchStatus.COMPLETED);

    assertRecovered(batchDate, sellerIds, residual);
  }

  @Test
  @DisplayName("잔류 실행이 정체됐어도 다른 연결이 그 날짜의 잠금을 쥐고 있으면 정리하지 않는다")
  void keepsResidualWhileLockIsHeld() throws Exception {
    LocalDate batchDate = LocalDate.of(2026, 8, 2);
    List<UUID> sellerIds = interruptedAfterFirstSeller(batchDate);
    JobExecution residual = residual(batchDate, Duration.ofHours(1));

    try (Connection other =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_lock(?, ?)")) {
      lock.setInt(1, DailySettlementLock.NAMESPACE);
      lock.setInt(2, DailySettlementLock.key(batchDate));
      lock.executeQuery().close();

      assertThat(launcher.run(batchDate)).isEqualTo(BatchStatus.STARTED);
    }

    assertThat(statusOf(residual)).isEqualTo("STARTED");
    assertThat(batchCount(sellerIds.get(1))).isZero();
  }

  @Test
  @DisplayName("잔류 실행의 마지막 갱신이 정체 임계 시간 안이면 정리하지 않는다")
  void keepsRecentlyUpdatedResidual() {
    LocalDate batchDate = LocalDate.of(2026, 8, 3);
    List<UUID> sellerIds = interruptedAfterFirstSeller(batchDate);
    JobExecution residual = residual(batchDate, Duration.ofMinutes(1));

    assertThat(launcher.run(batchDate)).isEqualTo(BatchStatus.STARTED);

    assertThat(statusOf(residual)).isEqualTo("STARTED");
    assertThat(batchCount(sellerIds.get(1))).isZero();
  }

  @Test
  @DisplayName("기동 복구는 실행 중으로 남은 일배치를 찾아 정리·재시작하고, 스케줄을 끄면 기동 복구도 두지 않는다")
  void startupRecoveryResumesResidual() throws Exception {
    LocalDate batchDate = LocalDate.of(2026, 8, 4);
    List<UUID> sellerIds = interruptedAfterFirstSeller(batchDate);
    JobExecution residual = residual(batchDate, Duration.ofHours(1));
    assertThat(context.getBeanProvider(DailySettlementScheduler.class).getIfAvailable()).isNull();

    new DailySettlementScheduler(launcher, recovery, taskScheduler, clock)
        .recoverInterruptedBatches();

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (batchCount(sellerIds.get(2)) == 0) {
      assertThat(System.nanoTime()).as("기동 복구가 끝나지 않았다").isLessThan(deadline);
      TimeUnit.MILLISECONDS.sleep(100);
    }
    TimeUnit.MILLISECONDS.sleep(500);
    assertRecovered(batchDate, sellerIds, residual);
  }

  /** 판매자 3명 중 첫 판매자만 커밋한 채 실행자가 죽은 업무 DB 상태를 만든다. */
  private List<UUID> interruptedAfterFirstSeller(LocalDate batchDate) {
    Instant readyAt = SettlementBatch.cutoffOf(batchDate).minusSeconds(60);
    List<UUID> sellerIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    for (UUID seller : sellerIds) {
      long base = 10_000L;
      long fee = base * 15 / 100;
      SettlementItem item =
          SettlementItem.create(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              seller,
              new SettlementAmounts(base, 0, base, base, 0, fee, base - fee),
              0,
              readyAt.minusSeconds(600));
      item.markPayable(readyAt, readyAt, readyAt);
      items.save(item);
    }
    sellers.settle(sellerIds.get(0), batchDate).orElseThrow();
    return sellerIds;
  }

  /** 죽은 실행자의 실행 중 Job·Step 실행 기록을 만들고 마지막 갱신을 {@code idle} 전으로 돌린다. */
  private JobExecution residual(LocalDate batchDate, Duration idle) {
    try {
      JobExecution execution =
          jobRepository.createJobExecution(
              DailySettlementJobConfiguration.JOB_NAME,
              new JobParametersBuilder()
                  .addLocalDate(DailySettlementJobConfiguration.BATCH_DATE, batchDate)
                  .addString(DailySettlementLauncher.EXECUTOR_ID, DEAD_EXECUTOR, false)
                  .toJobParameters());
      LocalDateTime startedAt = LocalDateTime.now().minus(idle).minusMinutes(1);
      execution.setStartTime(startedAt);
      execution.setStatus(BatchStatus.STARTED);
      jobRepository.update(execution);
      StepExecution step = execution.createStepExecution("settleSellers");
      step.setStartTime(startedAt);
      step.setStatus(BatchStatus.STARTED);
      jobRepository.add(step);
      LocalDateTime lastUpdated = LocalDateTime.now().minus(idle);
      jdbc.update(
          "UPDATE settlement.batch_job_execution SET create_time = ?, last_updated = ?"
              + " WHERE job_execution_id = ?",
          startedAt,
          lastUpdated,
          execution.getId());
      jdbc.update(
          "UPDATE settlement.batch_step_execution SET last_updated = ? WHERE job_execution_id = ?",
          lastUpdated,
          execution.getId());
      return execution;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertRecovered(LocalDate batchDate, List<UUID> sellerIds, JobExecution residual) {
    Map<String, Object> recovered =
        jdbc.queryForMap(
            "SELECT status, exit_code, exit_message FROM settlement.batch_job_execution"
                + " WHERE job_execution_id = ?",
            residual.getId());
    assertThat(recovered).containsEntry("status", "FAILED").containsEntry("exit_code", "FAILED");
    assertThat((String) recovered.get("exit_message"))
        .contains("recovered after forced termination; executor=" + DEAD_EXECUTOR);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM settlement.batch_step_execution WHERE job_execution_id = ?",
                String.class,
                residual.getId()))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT e.status FROM settlement.batch_job_execution e"
                    + " WHERE e.job_instance_id = ? ORDER BY e.job_execution_id DESC LIMIT 1",
                String.class,
                residual.getJobInstance().getInstanceId()))
        .isEqualTo("COMPLETED");
    for (UUID seller : sellerIds) {
      assertThat(batchCount(seller)).as("seller batches").isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM settlement.payouts p JOIN settlement.settlement_batches b"
                      + " ON b.id = p.batch_id WHERE b.seller_id = ? AND b.batch_date = ?",
                  Long.class,
                  seller,
                  batchDate))
          .isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.settlement_items i"
                    + " WHERE i.seller_id IN (?, ?, ?) AND i.status <> 'PAYOUT_RESERVED'",
                Long.class,
                sellerIds.toArray()))
        .isZero();
  }

  private String statusOf(JobExecution execution) {
    return jdbc.queryForObject(
        "SELECT status FROM settlement.batch_job_execution WHERE job_execution_id = ?",
        String.class,
        execution.getId());
  }

  private long batchCount(UUID seller) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM settlement.settlement_batches WHERE seller_id = ?",
        Long.class,
        seller);
  }
}
