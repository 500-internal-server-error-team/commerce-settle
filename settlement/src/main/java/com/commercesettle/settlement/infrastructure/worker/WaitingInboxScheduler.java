package com.commercesettle.settlement.infrastructure.worker;

import com.commercesettle.settlement.application.service.SettlementEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 선행 결제를 기다리는 구매확정을 짧은 주기로 다시 판정한다. 한 건이 실패해도 다음 건으로 넘어간다. */
@Component
@ConditionalOnProperty(name = "settlement.inbox-processor.enabled", havingValue = "true")
class WaitingInboxScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(WaitingInboxScheduler.class);

  private final SettlementEventService service;
  private final int batchSize;

  WaitingInboxScheduler(
      SettlementEventService service,
      @Value("${settlement.inbox-processor.batch-size}") int batchSize) {
    this.service = service;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${settlement.inbox-processor.interval-ms}")
  void poll() {
    for (int processed = 0; processed < batchSize; processed++) {
      try {
        if (!service.processNextWaiting()) {
          return;
        }
      } catch (RuntimeException e) {
        LOGGER
            .atError()
            .setCause(e)
            .addKeyValue("event", "settlement.inbox")
            .addKeyValue("result", "ERROR")
            .addKeyValue("errorType", e.getClass().getName())
            .log("Waiting inbox event failed; it stays waiting");
      }
    }
  }
}
