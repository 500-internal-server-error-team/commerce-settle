package com.commercesettle.settlement.infrastructure.worker;

import com.commercesettle.settlement.application.port.ReconciliationStore;
import com.commercesettle.settlement.application.service.ReconciliationService;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 10:30 이후의 놓친 당일 실행과 영속 미완료 회차를 같은 경로로 진행한다. */
@Component
@ConditionalOnProperty(name = "settlement.reconciliation.enabled", havingValue = "true")
public class ReconciliationScheduler {
  private final ReconciliationService service;
  private final Clock clock;
  private final ReconciliationStore store;

  public ReconciliationScheduler(
      ReconciliationService service, Clock clock, ReconciliationStore store) {
    this.service = service;
    this.clock = clock;
    this.store = store;
  }

  @Scheduled(cron = "0 30 10 * * *", zone = "Asia/Seoul")
  public void daily() {
    requestDue();
  }

  @Scheduled(fixedDelayString = "${settlement.reconciliation.poll-ms:1000}")
  public void poll() {
    requestDue();
    service.tick();
  }

  private void requestDue() {
    var now = clock.instant().atZone(ZoneId.of("Asia/Seoul"));
    var date =
        now.toLocalTime().isBefore(LocalTime.of(10, 30))
            ? now.toLocalDate().minusDays(1)
            : now.toLocalDate();
    var target = date.atTime(10, 0).atZone(now.getZone()).toInstant();
    var cutoff =
        store
            .latestCutoff()
            .map(last -> last.plus(java.time.Duration.ofDays(1)))
            .filter(next -> next.isBefore(target))
            .orElse(target);
    service.request(cutoff, "scheduler");
  }
}
