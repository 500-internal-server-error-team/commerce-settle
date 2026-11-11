package com.commercesettle.settlement.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;

/**
 * 기본 Batch Timer·active 지표에 없는 최근 회차의 상태·진행량만 보완한다. ID는 동시 회차의 순서를 구분하는 메모리 값이며 label에 넣지 않는다.
 * 재기동하면 초기화되므로 이전 실행은 Spring Batch DB 기록과 Prometheus의 이전 구간에서 확인한다.
 */
public final class DailySettlementProgress
    implements JobExecutionListener, StepExecutionListener, ChunkListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(DailySettlementProgress.class);
  private final AtomicReference<JobState> job =
      new AtomicReference<>(new JobState(-1, 0, 0, 0, Double.NaN));
  private final AtomicReference<Progress> step =
      new AtomicReference<>(new Progress(-1, -1, 0, 0, 0));
  private volatile boolean ready;

  public DailySettlementProgress(MeterRegistry meters, String jobName, String stepName) {
    if (meters == null) return;
    try {
      gauge(meters, "settlement.batch.observer.ready", jobName, x -> x.ready ? 1 : 0);
      gauge(meters, "settlement.batch.last.state", jobName, x -> x.job.get().state());
      gauge(meters, "settlement.batch.last.started", jobName, x -> x.job.get().started());
      gauge(meters, "settlement.batch.last.ended", jobName, x -> x.job.get().ended());
      gauge(meters, "settlement.batch.last.duration", jobName, x -> x.job.get().duration());
      for (String kind : new String[] {"read", "written", "skipped"}) {
        Gauge.builder("settlement.batch.step.items", this, x -> x.items(kind))
            .tags("batch.job.name", jobName, "batch.step.name", stepName, "kind", kind)
            .register(meters);
      }
      ready = true;
    } catch (RuntimeException failure) {
      LOGGER.warn(
          "Daily batch metrics could not be registered ({})", failure.getClass().getSimpleName());
    }
  }

  private void gauge(
      MeterRegistry meters,
      String name,
      String jobName,
      ToDoubleFunction<DailySettlementProgress> value) {
    var gauge = Gauge.builder(name, this, value).tag("batch.job.name", jobName);
    if (name.endsWith(".started") || name.endsWith(".ended") || name.endsWith(".duration")) {
      gauge.baseUnit("seconds");
    }
    gauge.register(meters);
  }

  private double items(String kind) {
    Progress current = step.get();
    // 새 Job이 시작됐지만 아직 Step이 없으면 이전 회차의 진행량을 보여 주지 않는다.
    if (current.jobId() != job.get().id()) return 0;
    return switch (kind) {
      case "read" -> current.read();
      case "written" -> current.written();
      default -> current.skipped();
    };
  }

  @Override
  public void beforeJob(JobExecution execution) {
    guarded(
        () ->
            job.updateAndGet(
                old ->
                    execution.getId() >= old.id()
                        ? new JobState(
                            execution.getId(), epoch(execution.getStartTime()), 0, 1, Double.NaN)
                        : old));
  }

  @Override
  public void afterJob(JobExecution execution) {
    guarded(
        () ->
            job.updateAndGet(
                old ->
                    execution.getId() >= old.id()
                        ? new JobState(
                            execution.getId(),
                            epoch(execution.getStartTime()),
                            epoch(execution.getEndTime()),
                            state(execution),
                            seconds(execution.getStartTime(), execution.getEndTime()))
                        : old));
  }

  @Override
  public void beforeStep(StepExecution execution) {
    capture(execution);
  }

  @Override
  public void afterChunk(ChunkContext context) {
    capture(context.getStepContext().getStepExecution());
  }

  @Override
  public org.springframework.batch.core.ExitStatus afterStep(StepExecution execution) {
    capture(execution);
    return null;
  }

  private void capture(StepExecution execution) {
    guarded(
        () ->
            step.updateAndGet(
                old ->
                    execution.getJobExecutionId() > old.jobId()
                            || (execution.getJobExecutionId() == old.jobId()
                                && execution.getId() >= old.stepId())
                        ? new Progress(
                            execution.getJobExecutionId(),
                            execution.getId(),
                            execution.getReadCount(),
                            execution.getWriteCount(),
                            execution.getSkipCount())
                        : old));
  }

  private static int state(JobExecution execution) {
    BatchStatus status = execution.getStatus();
    if (status == BatchStatus.COMPLETED) {
      return "COMPLETED_WITH_SKIPS".equals(execution.getExitStatus().getExitCode())
              || execution.getStepExecutions().stream().anyMatch(s -> s.getSkipCount() > 0)
          ? 3
          : 2;
    }
    if (status == BatchStatus.FAILED || status == BatchStatus.ABANDONED) return 4;
    if (status == BatchStatus.STOPPED) return 5;
    return status.isRunning() ? 1 : 6;
  }

  private static double epoch(LocalDateTime time) {
    return time == null
        ? 0
        : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() / 1000.0;
  }

  private static double seconds(LocalDateTime start, LocalDateTime end) {
    if (start == null || end == null || end.isBefore(start)) return Double.NaN;
    Duration elapsed = Duration.between(start, end);
    return elapsed.toNanos() / 1_000_000_000.0;
  }

  private static void guarded(Runnable capture) {
    try {
      capture.run();
    } catch (RuntimeException failure) {
      LOGGER.warn(
          "Daily batch progress could not be observed ({})", failure.getClass().getSimpleName());
    }
  }

  private record JobState(long id, double started, double ended, int state, double duration) {}

  private record Progress(long jobId, long stepId, long read, long written, long skipped) {}
}
