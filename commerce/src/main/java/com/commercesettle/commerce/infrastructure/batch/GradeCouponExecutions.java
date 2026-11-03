package com.commercesettle.commerce.infrastructure.batch;

import com.commercesettle.commerce.application.port.CouponIssueRunExecutions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * 월간 회차별 실행자 잠금과 진행 중 실행의 정리(정산 일배치와 같은 방식). 잠금은 PostgreSQL 세션 advisory lock을 전용 연결에 잡고 Job이 끝날 때까지
 * 쥔다. 실행 프로세스가 죽어 연결이 끊기면 DB가 잠금을 풀어 주므로, 잠금을 잡았다는 것은 그 회차의 실행자가 없다는 뜻이다. 잠금을 잡았는데도 진행 중으로 남은 실행은
 * Job·Step의 마지막 갱신이 임계 시간보다 오래됐을 때만 FAILED로 정리해 같은 식별자로 재시작할 수 있게 한다.
 */
@Component
public class GradeCouponExecutions implements CouponIssueRunExecutions {
  private static final Logger LOGGER = LoggerFactory.getLogger(GradeCouponExecutions.class);

  // 월간 우수고객 회차 전용 잠금 공간. 두 번째 키는 runId에서 만든다.
  public static final int NAMESPACE = 0x6C0D_1E01;

  private final DataSource dataSource;
  private final JobExplorer explorer;
  private final JobRepository repository;
  private final Duration staleAfter;

  GradeCouponExecutions(
      DataSource dataSource,
      JobExplorer explorer,
      JobRepository repository,
      @Value("${app.coupons.grade-stale-after:PT10M}") Duration staleAfter) {
    if (staleAfter.isNegative() || staleAfter.isZero()) {
      throw new IllegalArgumentException("app.coupons.grade-stale-after must be positive");
    }
    this.dataSource = dataSource;
    this.explorer = explorer;
    this.repository = repository;
    this.staleAfter = staleAfter;
  }

  public static int key(UUID runId) {
    return runId.hashCode();
  }

  @Override
  public Optional<Held> tryLock(UUID runId) {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      if (call(connection, "SELECT pg_try_advisory_lock(?, ?)", runId)) {
        Connection held = connection;
        return Optional.of(() -> release(held, runId));
      }
      connection.close();
      return Optional.empty();
    } catch (SQLException e) {
      closeQuietly(connection, e);
      throw new DataAccessResourceFailureException("grade coupon run lock is unavailable", e);
    }
  }

  @Override
  public boolean hasActiveExecution(UUID runId) {
    return !running(runId).isEmpty();
  }

  /** 남은 실행이 모두 정체됐으면 FAILED로 정리하고 true다. 하나라도 최근에 갱신됐거나 판정할 수 없으면 바꾸지 않는다. 실행자 잠금을 쥔 채 호출한다. */
  boolean recoverIfStale(UUID runId) {
    try {
      List<JobExecution> executions = running(runId);
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
          .addKeyValue("event", "coupon.grade-job")
          .addKeyValue("runId", runId)
          .addKeyValue("result", "RECOVERY_UNDECIDED")
          .log("Interrupted grade coupon job was left for operator review");
      return false;
    }
  }

  private List<JobExecution> running(UUID runId) {
    String id = runId.toString();
    return explorer.findRunningJobExecutions(GradeCouponJobConfiguration.JOB_NAME).stream()
        .filter(
            execution ->
                id.equals(
                    execution.getJobParameters().getString(GradeCouponJobConfiguration.RUN_ID)))
        .toList();
  }

  private void markFailed(JobExecution execution) {
    LocalDateTime now = LocalDateTime.now();
    String description = "recovered after forced termination";
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
        .addKeyValue("event", "coupon.grade-job")
        .addKeyValue("jobExecutionId", execution.getId())
        .addKeyValue("result", "RECOVERED")
        .log("Interrupted grade coupon job was marked failed for restart");
  }

  // Job 실행의 갱신 시각은 Step 반복마다 바뀌지 않으므로 Step 실행의 갱신 시각까지 본다.
  private static LocalDateTime lastActivity(JobExecution execution) {
    return Stream.concat(
            Stream.of(execution.getLastUpdated()),
            execution.getStepExecutions().stream().map(StepExecution::getLastUpdated))
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .or(() -> Optional.ofNullable(execution.getStartTime()))
        .orElseGet(execution::getCreateTime);
  }

  /** 풀로 돌려주기 전에 잠금을 푼다. 연결이 이미 끊겼으면 DB가 잠금을 풀었으므로 닫기만 한다. */
  private static void release(Connection connection, UUID runId) {
    try (connection) {
      call(connection, "SELECT pg_advisory_unlock(?, ?)", runId);
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("grade coupon run lock release failed", e);
    }
  }

  private static boolean call(Connection connection, String sql, UUID runId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, NAMESPACE);
      statement.setInt(2, key(runId));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private static void closeQuietly(Connection connection, Exception cause) {
    if (connection == null) return;
    try {
      connection.close();
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }
}
