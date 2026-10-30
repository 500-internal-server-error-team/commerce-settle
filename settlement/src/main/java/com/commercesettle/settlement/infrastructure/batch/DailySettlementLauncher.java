package com.commercesettle.settlement.infrastructure.batch;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import java.time.Clock;
import java.time.LocalDate;
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
 * 새로 실행하지 않고 완료로 돌려준다(저장된 배치가 결과다). 실행 중으로 남은 실행은 건드리지 않는다(ADR 0007 D7의 강제 종료 복구는 후속).
 */
@Component
public class DailySettlementLauncher {
  private static final Logger LOGGER = LoggerFactory.getLogger(DailySettlementLauncher.class);

  private final JobLauncher jobLauncher;
  private final Job dailySettlementJob;
  private final Clock clock;

  DailySettlementLauncher(JobLauncher jobLauncher, Job dailySettlementJob, Clock clock) {
    this.jobLauncher = jobLauncher;
    this.dailySettlementJob = dailySettlementJob;
    this.clock = clock;
  }

  public BatchStatus run(LocalDate batchDate) {
    return run(batchDate, null);
  }

  public BatchStatus runFixture(LocalDate batchDate, Set<UUID> itemIds) {
    if (itemIds.isEmpty()) throw new IllegalArgumentException("Fixture item IDs are required");
    return run(batchDate, itemIds);
  }

  private BatchStatus run(LocalDate batchDate, Set<UUID> itemIds) {
    if (SettlementBatch.cutoffOf(batchDate).isAfter(clock.instant())) {
      throw new IllegalArgumentException("cutoff of batchDate has not passed yet");
    }
    JobParametersBuilder builder =
        new JobParametersBuilder()
            .addLocalDate(DailySettlementJobConfiguration.BATCH_DATE, batchDate);
    if (itemIds != null)
      builder.addString(
          "fixtureItemIds",
          itemIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(",")));
    JobParameters parameters = builder.toJobParameters();
    try {
      BatchStatus status = jobLauncher.run(dailySettlementJob, parameters).getStatus();
      LOGGER
          .atInfo()
          .addKeyValue("event", "settlement.daily-batch")
          .addKeyValue("batchDate", batchDate)
          .addKeyValue("result", status)
          .log("Daily settlement finished");
      return status;
    } catch (JobInstanceAlreadyCompleteException e) {
      return BatchStatus.COMPLETED;
    } catch (JobExecutionAlreadyRunningException e) {
      LOGGER
          .atWarn()
          .addKeyValue("event", "settlement.daily-batch")
          .addKeyValue("batchDate", batchDate)
          .addKeyValue("result", "ALREADY_RUNNING")
          .log("Daily settlement is already running");
      return BatchStatus.STARTED;
    } catch (JobRestartException | JobParametersInvalidException e) {
      throw new IllegalStateException("daily settlement cannot be started", e);
    }
  }
}
