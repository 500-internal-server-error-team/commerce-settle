package com.commercesettle.settlement.infrastructure.batch;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.batch.core.BatchStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 10:00 Asia/Seoul 마감 직후 그날의 일배치를 시작한다. 주말·공휴일도 같다(ADR 0007 D3). 스케줄러 스레드를 오래 잡지 않도록 실행은 별도 스레드에
 * 넘긴다(ADR 0008). 기동하면 실행 중으로 남은 일배치를 다시 실행해 강제 종료 잔류를 복구한다. 아직 정체 임계 시간 안이면 그 간격마다 다시 확인한다.
 */
@Component
@ConditionalOnProperty(
    prefix = "settlement.daily-batch",
    name = "schedule-enabled",
    havingValue = "true",
    matchIfMissing = true)
class DailySettlementScheduler {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

  private final DailySettlementLauncher launcher;
  private final DailySettlementRecovery recovery;
  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final TaskExecutor executor = new SimpleAsyncTaskExecutor("daily-settlement-");

  DailySettlementScheduler(
      DailySettlementLauncher launcher,
      DailySettlementRecovery recovery,
      TaskScheduler taskScheduler,
      Clock clock) {
    this.launcher = launcher;
    this.recovery = recovery;
    this.taskScheduler = taskScheduler;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
  void startTodaysBatch() {
    LocalDate batchDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    executor.execute(() -> launcher.run(batchDate));
  }

  @EventListener(ApplicationReadyEvent.class)
  void recoverInterruptedBatches() {
    executor.execute(() -> recovery.runningBatchDates().forEach(this::resume));
  }

  // 다른 실행자가 실제로 실행 중이면 그 실행이 끝난 뒤 완료로 돌아와 멈춘다.
  private void resume(LocalDate batchDate) {
    if (launcher.run(batchDate) == BatchStatus.STARTED) {
      taskScheduler.schedule(
          () -> executor.execute(() -> resume(batchDate)),
          clock.instant().plus(recovery.staleAfter()));
    }
  }
}
