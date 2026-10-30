package com.commercesettle.settlement.infrastructure.batch;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 10:00 Asia/Seoul 마감 직후 그날의 일배치를 시작한다. 주말·공휴일도 같다(ADR 0007 D3). 스케줄러 스레드를 오래 잡지 않도록 실행은 별도 스레드에
 * 넘긴다(ADR 0008).
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
  private final Clock clock;
  private final TaskExecutor executor = new SimpleAsyncTaskExecutor("daily-settlement-");

  DailySettlementScheduler(DailySettlementLauncher launcher, Clock clock) {
    this.launcher = launcher;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
  void startTodaysBatch() {
    LocalDate batchDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    executor.execute(() -> launcher.run(batchDate));
  }
}
