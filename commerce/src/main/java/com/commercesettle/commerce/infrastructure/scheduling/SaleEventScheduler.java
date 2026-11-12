package com.commercesettle.commerce.infrastructure.scheduling;

import com.commercesettle.commerce.application.service.SaleEventService;
import com.commercesettle.observability.RepeatedWarning;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 시작 시각이 된 이벤트를 열고 종료 시각이 지난 이벤트를 닫는다. 신청 가능 여부는 요청 때도 서버 시각으로 기간을 검사하므로 이 작업이 늦어도 시작한 이벤트의 신청을 막거나
 * 종료 이벤트에 신청을 받지 않는다. 이벤트마다 별도 트랜잭션으로 닫아 한 건의 잠금 대기·실패가 나머지를 막지 않으며, 실패한 건은 다음 실행에서 다시 찾는다.
 */
public class SaleEventScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(SaleEventScheduler.class);
  private final RepeatedWarning stepFailed = new RepeatedWarning();

  private final SaleEventService events;
  private final Clock clock;
  private final int batchSize;

  public SaleEventScheduler(SaleEventService events, Clock clock, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("Invalid sale event batch size");
    }
    this.events = events;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${app.sale-events.schedule-interval-ms:60000}",
      fixedDelayString = "${app.sale-events.schedule-interval-ms:60000}")
  public void run() {
    Instant now = clock.instant();
    try {
      events.openStarted(now, batchSize);
    } catch (RuntimeException e) {
      warn("sale-event.open-failed", null, e);
    }
    for (UUID eventId : events.endedEventIds(now, batchSize)) {
      try {
        events.closeIfEnded(eventId, now);
      } catch (RuntimeException e) {
        warn("sale-event.close-failed", eventId, e);
      }
    }
  }

  // 예외 메시지는 남기지 않고 종류만 기록한다.
  private void warn(String event, UUID eventId, RuntimeException e) {
    stepFailed
        .at(LOG)
        .addKeyValue("event", event)
        .addKeyValue("eventId", eventId == null ? "" : eventId.toString())
        .addKeyValue("errorType", e.getClass().getName())
        .log("Sale event schedule step failed; it will be retried on the next run");
  }
}
