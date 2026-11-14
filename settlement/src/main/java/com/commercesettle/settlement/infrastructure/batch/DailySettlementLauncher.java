package com.commercesettle.settlement.infrastructure.batch;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.stereotype.Component;

/**
 * batchDate를 식별 파라미터로 일배치를 실행한다. 직전 실행이 실패·중단이면 Spring Batch가 같은 JobInstance를 이어서 재시작하고, 이미 완료됐으면
 * 새로 실행하지 않고 완료로 돌려준다(저장된 배치가 결과다). 같은 batchDate의 실행자 잠금을 쥔 채 실행하며, 다른 실행자가 쥐고 있으면 시작하지 않고 실행 중으로
 * 돌려준다. 잠금을 잡았는데 실행 중으로 남은 실행이 있으면 강제 종료 잔류로 보고, 정체됐을 때만 정리한 뒤 한 번 다시 실행한다. 실행자 ID는 비식별 파라미터로 남긴다.
 */
@Component
public class DailySettlementLauncher
    implements com.commercesettle.settlement.application.port.DemoBatchLauncher {
  private static final Logger LOGGER = LoggerFactory.getLogger(DailySettlementLauncher.class);

  static final String EXECUTOR_ID = "executorId";

  private final JobLauncher jobLauncher;
  private final Job dailySettlementJob;
  private final DailySettlementLock lock;
  private final DailySettlementRecovery recovery;
  private final Clock clock;
  private final boolean demoEnabled;
  private final String executorId = "settlement-" + UUID.randomUUID();

  DailySettlementLauncher(
      JobLauncher jobLauncher,
      Job dailySettlementJob,
      DailySettlementLock lock,
      DailySettlementRecovery recovery,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value("${app.demo.enabled:false}")
          boolean demoEnabled) {
    this.jobLauncher = jobLauncher;
    this.dailySettlementJob = dailySettlementJob;
    this.lock = lock;
    this.recovery = recovery;
    this.clock = clock;
    this.demoEnabled = demoEnabled;
  }

  public BatchStatus run(LocalDate batchDate) {
    return run(batchDate, null);
  }

  public BatchStatus runFixture(LocalDate batchDate, Set<UUID> itemIds) {
    if (itemIds.isEmpty()) throw new IllegalArgumentException("Fixture item IDs are required");
    return run(batchDate, itemIds);
  }

  private BatchStatus run(LocalDate batchDate, Set<UUID> itemIds) {
    if (demoEnabled) throw new IllegalStateException("Normal daily batch is disabled in demo mode");
    if (SettlementBatch.cutoffOf(batchDate).isAfter(clock.instant())) {
      throw new IllegalArgumentException("cutoff of batchDate has not passed yet");
    }
    Optional<DailySettlementLock.Held> held = lock.tryAcquire(batchDate);
    if (held.isEmpty()) {
      logAlreadyRunning(batchDate);
      return BatchStatus.STARTED;
    }
    try (DailySettlementLock.Held ignored = held.get()) {
      return launch(batchDate, itemIds, true);
    }
  }

  @Override
  public String runDemo(
      com.commercesettle.settlement.application.port.DemoSettlementStore.Run run) {
    if (!demoEnabled) throw new IllegalStateException("Demo mode is disabled");
    if (run.cutoffAt().isAfter(clock.instant()))
      throw new IllegalArgumentException("Future demo cutoff");
    var held = lock.tryAcquire(run.id());
    if (held.isEmpty()) return BatchStatus.STARTED.name();
    try (var ignored = held.get()) {
      return launchDemo(run, true).name();
    }
  }

  private BatchStatus launchDemo(
      com.commercesettle.settlement.application.port.DemoSettlementStore.Run run, boolean recover) {
    var parameters =
        new JobParametersBuilder()
            .addLocalDate(
                DailySettlementJobConfiguration.BATCH_DATE,
                run.cutoffAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate())
            .addString("demoRunId", run.id().toString())
            .addString("demoCutoffAt", run.cutoffAt().toString())
            .addString(EXECUTOR_ID, executorId, false)
            .toJobParameters();
    try {
      var result = jobLauncher.run(dailySettlementJob, parameters).getStatus();
      LOGGER
          .atInfo()
          .addKeyValue("event", "settlement.demo-batch")
          .addKeyValue("runId", run.id())
          .addKeyValue("cutoffAt", run.cutoffAt())
          .addKeyValue("result", result)
          .log("Demo settlement finished");
      return result;
    } catch (JobInstanceAlreadyCompleteException e) {
      return BatchStatus.COMPLETED;
    } catch (JobExecutionAlreadyRunningException e) {
      return recover && recovery.recoverDemoIfStale(run.id())
          ? launchDemo(run, false)
          : BatchStatus.STARTED;
    } catch (JobRestartException | JobParametersInvalidException e) {
      throw new IllegalStateException("Demo batch cannot start", e);
    }
  }

  private BatchStatus launch(LocalDate batchDate, Set<UUID> itemIds, boolean mayRecover) {
    JobParametersBuilder builder =
        new JobParametersBuilder()
            .addLocalDate(DailySettlementJobConfiguration.BATCH_DATE, batchDate)
            .addString(EXECUTOR_ID, executorId, false);
    if (itemIds != null)
      builder.addString(
          "fixtureItemIds",
          itemIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(",")));
    JobParameters parameters = builder.toJobParameters();
    try {
      BatchStatus status = jobLauncher.run(dailySettlementJob, parameters).getStatus();
      // 하루 한 번 실행 여부를 확인하도록 성공도 남긴다. 등급 규칙의 INFO 예외다.
      (status == BatchStatus.COMPLETED ? LOGGER.atInfo() : LOGGER.atWarn())
          .addKeyValue("event", "settlement.daily-batch")
          .addKeyValue("batchDate", batchDate)
          .addKeyValue("result", status)
          .log("Daily settlement finished");
      return status;
    } catch (JobInstanceAlreadyCompleteException e) {
      return BatchStatus.COMPLETED;
    } catch (JobExecutionAlreadyRunningException e) {
      if (mayRecover && recovery.recoverIfStale(batchDate)) {
        return launch(batchDate, itemIds, false);
      }
      logAlreadyRunning(batchDate);
      return BatchStatus.STARTED;
    } catch (JobRestartException | JobParametersInvalidException e) {
      throw new IllegalStateException("daily settlement cannot be started", e);
    }
  }

  private static void logAlreadyRunning(LocalDate batchDate) {
    LOGGER
        .atDebug()
        .addKeyValue("event", "settlement.daily-batch")
        .addKeyValue("batchDate", batchDate)
        .addKeyValue("result", "ALREADY_RUNNING")
        .log("Daily settlement is already running");
  }
}
