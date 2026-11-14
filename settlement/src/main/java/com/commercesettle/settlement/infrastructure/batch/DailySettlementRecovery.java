package com.commercesettle.settlement.infrastructure.batch;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 강제 종료로 실행 중 상태에 남은 일배치 실행을 정리한다. 호출자가 그 batchDate의 실행자 잠금을 쥐고 있어야 한다. 잠금을 잡았다는 것으로 실행자가 없음을,
 * Job·Step의 마지막 갱신이 임계 시간보다 오래됐다는 것으로 연결만 끊긴 살아 있는 실행자가 아님을 확인한다. 재시작 위치는 업무 DB가 정하므로 정리 뒤 같은
 * batchDate로 다시 실행하면 커밋하지 않은 판매자만 처리한다. 판정할 수 없으면 정리하지 않는다.
 */
@Component
class DailySettlementRecovery {
  private static final Logger LOGGER = LoggerFactory.getLogger(DailySettlementRecovery.class);

  private final JobExplorer explorer;
  private final JobRepository repository;
  private final Duration staleAfter;

  DailySettlementRecovery(
      JobExplorer explorer,
      JobRepository repository,
      @Value("${settlement.daily-batch.stale-after}") Duration staleAfter) {
    if (staleAfter.isNegative() || staleAfter.isZero()) {
      throw new IllegalArgumentException("settlement.daily-batch.stale-after must be positive");
    }
    this.explorer = explorer;
    this.repository = repository;
    this.staleAfter = staleAfter;
  }

  Duration staleAfter() {
    return staleAfter;
  }

  /** 실행 중 상태로 남은 일배치의 batchDate. 실제로 실행 중인 것도 포함한다. */
  Set<LocalDate> runningBatchDates() {
    return running()
        .filter(e -> e.getJobParameters().getString("demoRunId") == null)
        .map(DailySettlementRecovery::batchDateOf)
        .collect(Collectors.toSet());
  }

  /** 남은 실행이 모두 정체됐으면 FAILED로 정리하고 true를 돌려준다. 하나라도 최근에 갱신됐거나 판정할 수 없으면 바꾸지 않는다. */
  boolean recoverIfStale(LocalDate batchDate) {
    return recover(
        execution ->
            execution.getJobParameters().getString("demoRunId") == null
                && batchDate.equals(batchDateOf(execution)),
        batchDate);
  }

  boolean recoverDemoIfStale(java.util.UUID id) {
    return recover(
        execution -> id.toString().equals(execution.getJobParameters().getString("demoRunId")),
        null);
  }

  private boolean recover(
      java.util.function.Predicate<JobExecution> selected, LocalDate batchDate) {
    try {
      List<JobExecution> executions = running().filter(selected).toList();
      LocalDateTime threshold = LocalDateTime.now().minus(staleAfter);
      if (executions.isEmpty()
          || executions.stream()
              .anyMatch(execution -> lastActivity(execution).isAfter(threshold))) {
        return false;
      }
      executions.forEach(this::markFailed);
      return true;
    } catch (RuntimeException e) {
      LOGGER
          .atWarn()
          .setCause(e)
          .addKeyValue("event", "settlement.daily-batch")
          .addKeyValue("batchDate", batchDate)
          .addKeyValue("result", "RECOVERY_UNDECIDED")
          .log("Interrupted daily settlement was left for operator review");
      return false;
    }
  }

  private Stream<JobExecution> running() {
    return explorer.findRunningJobExecutions(DailySettlementJobConfiguration.JOB_NAME).stream();
  }

  private void markFailed(JobExecution execution) {
    LocalDateTime now = LocalDateTime.now();
    String executor = execution.getJobParameters().getString(DailySettlementLauncher.EXECUTOR_ID);
    String description = "recovered after forced termination; executor=" + executor;
    for (StepExecution step : execution.getStepExecutions()) {
      if (step.getStatus().isRunning()) {
        step.setStatus(BatchStatus.FAILED);
        step.setExitStatus(ExitStatus.FAILED.addExitDescription(description));
        step.setEndTime(now);
        repository.update(step);
      }
    }
    execution.setStatus(BatchStatus.FAILED);
    execution.setExitStatus(ExitStatus.FAILED.addExitDescription(description));
    execution.setEndTime(now);
    repository.update(execution);
    LOGGER
        .atWarn()
        .addKeyValue("event", "settlement.daily-batch")
        .addKeyValue("batchDate", batchDateOf(execution))
        .addKeyValue("jobExecutionId", execution.getId())
        .addKeyValue("executorId", executor)
        .addKeyValue("result", "RECOVERED")
        .log("Interrupted daily settlement was marked failed for restart");
  }

  // Job 실행의 갱신 시각은 chunk마다 바뀌지 않으므로 Step 실행의 갱신 시각까지 본다. 갱신 기록이 없을 때만 시작·생성 시각을 쓴다.
  private static LocalDateTime lastActivity(JobExecution execution) {
    return Stream.concat(
            Stream.of(execution.getLastUpdated()),
            execution.getStepExecutions().stream().map(StepExecution::getLastUpdated))
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .or(() -> Optional.ofNullable(execution.getStartTime()))
        .orElseGet(execution::getCreateTime);
  }

  private static LocalDate batchDateOf(JobExecution execution) {
    return execution.getJobParameters().getLocalDate(DailySettlementJobConfiguration.BATCH_DATE);
  }
}
