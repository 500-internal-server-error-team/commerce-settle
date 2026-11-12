package com.commercesettle.commerce.infrastructure.batch;

import com.commercesettle.commerce.application.port.CouponIssueRunExecutions;
import com.commercesettle.commerce.application.service.GradeCouponService;
import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 혜택 월의 회차를 targetMonth+runId 식별 파라미터로 실행한다. 실패·중단된 실행은 Spring Batch가 같은 JobInstance로 재시작하고,
 * 완료·만료·취소된 회차는 실행하지 않는다. 다른 실행이 진행 중이면 시작하지 않는다. 진행 중으로 남은 강제 종료 실행의 복구는 운영 절차(후속)다.
 */
@Component
public class GradeCouponLauncher {
  private static final Logger LOGGER = LoggerFactory.getLogger(GradeCouponLauncher.class);

  private final JobLauncher jobLauncher;
  private final Job gradeCouponJob;
  private final GradeCouponService grades;
  private final GradeCouponExecutions executions;

  GradeCouponLauncher(
      JobLauncher jobLauncher,
      Job gradeCouponJob,
      GradeCouponService grades,
      GradeCouponExecutions executions) {
    this.jobLauncher = jobLauncher;
    this.gradeCouponJob = gradeCouponJob;
    this.grades = grades;
    this.executions = executions;
  }

  /** 혜택 월보다 이전인데 끝나지 않은 회차의 혜택 월. */
  public List<LocalDate> unfinishedMonthsBefore(LocalDate targetMonth) {
    return grades.unfinishedMonthsBefore(targetMonth);
  }

  /** 회차 상태를 돌려준다. 혜택 월이 아직 마감되지 않았으면 비어 있다. */
  public Optional<CouponIssueRunStatus> run(LocalDate targetMonth) {
    Optional<CouponIssueRun> current = grades.currentRun(targetMonth);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    CouponIssueRun run = current.get();
    if (run.getStatus() != CouponIssueRunStatus.PREPARING
        && run.getStatus() != CouponIssueRunStatus.ISSUING) {
      recoverLeftover(run);
      return Optional.of(run.getStatus());
    }
    Optional<CouponIssueRunExecutions.Held> held = executions.tryLock(run.getId());
    if (held.isEmpty()) {
      logAlreadyRunning(run);
      return Optional.of(run.getStatus());
    }
    try (CouponIssueRunExecutions.Held ignored = held.get()) {
      launch(run, true);
    }
    return grades.currentRun(targetMonth).map(CouponIssueRun::getStatus);
  }

  private void launch(CouponIssueRun run, boolean mayRecover) {
    JobParameters parameters =
        new JobParametersBuilder()
            .addLocalDate(GradeCouponJobConfiguration.TARGET_MONTH, run.getTargetMonth())
            .addString(GradeCouponJobConfiguration.RUN_ID, run.getId().toString())
            .toJobParameters();
    try {
      jobLauncher.run(gradeCouponJob, parameters);
    } catch (JobInstanceAlreadyCompleteException e) {
      // Job은 끝났지만 업무 종결이 남았을 수 있어 같은 회차를 다시 확인한다.
      grades.finish(run.getId());
    } catch (JobExecutionAlreadyRunningException e) {
      if (mayRecover && executions.recoverIfStale(run.getId())) {
        launch(run, false);
        return;
      }
      logAlreadyRunning(run);
    } catch (JobRestartException | JobParametersInvalidException e) {
      throw new IllegalStateException("grade coupon job cannot be started", e);
    }
  }

  /**
   * 업무가 끝난 회차(대상 0명 완료 등)에 강제 종료로 진행 중 실행만 남았으면, 실행자 잠금을 잡고 정체된 경우에만 FAILED로 정리한다. 업무는 다시 실행하지 않는다.
   * 남겨 두면 교정이 계속 실행 중으로 거절된다.
   */
  private void recoverLeftover(CouponIssueRun run) {
    if (!executions.hasActiveExecution(run.getId())) {
      return;
    }
    Optional<CouponIssueRunExecutions.Held> held = executions.tryLock(run.getId());
    if (held.isEmpty()) {
      logAlreadyRunning(run);
      return;
    }
    try (CouponIssueRunExecutions.Held ignored = held.get()) {
      executions.recoverIfStale(run.getId());
    }
  }

  private static void logAlreadyRunning(CouponIssueRun run) {
    LOGGER
        .atDebug()
        .addKeyValue("event", "coupon.grade-job")
        .addKeyValue("targetMonth", run.getTargetMonth())
        .addKeyValue("result", "ALREADY_RUNNING")
        .log("Grade coupon job is already running");
  }
}
