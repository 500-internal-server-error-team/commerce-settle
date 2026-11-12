package com.commercesettle.settlement.infrastructure.worker;

import com.commercesettle.settlement.application.port.ReconciliationStore;
import com.commercesettle.settlement.application.service.ReconciliationService;
import java.time.Clock;
import java.time.Instant;
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
  // 이미 요청한 당일 마감이면 매초 마지막 회차 조회와 등록을 생략한다. 재기동·다중 인스턴스는 멱등 등록으로 한 번 더 확인한다.
  private volatile Instant requested;

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
    if (target.equals(requested)) return;
    var cutoff =
        store
            .latestCutoff()
            .map(last -> last.plus(java.time.Duration.ofDays(1)))
            .filter(next -> next.isBefore(target))
            .orElse(target);
    service.request(cutoff, "scheduler");
    if (cutoff.equals(target)) requested = target;
  }
}
