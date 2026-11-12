package com.commercesettle.settlement.infrastructure.worker;

import com.commercesettle.settlement.application.exception.WaitingInboxFailedException;
import com.commercesettle.settlement.application.service.SettlementEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 선행 결제를 기다리는 구매확정을 짧은 주기로 다시 판정한다. 한 건이 실패하면 별도 트랜잭션에서 그 행을 미루고 다음 건으로 넘어가, 같은 행을 한 주기에 여러 번 선점하지
 * 않는다. 미루기나 선점 자체가 실패하면 이번 주기를 멈춘다.
 */
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
      } catch (WaitingInboxFailedException e) {
        log(e.getCause(), "Waiting inbox event failed; it is deferred and stays waiting");
        try {
          service.deferFailedWaiting(e.inboxId());
        } catch (RuntimeException deferFailure) {
          log(deferFailure, "Waiting inbox event could not be deferred; stopping this poll");
          return;
        }
      } catch (RuntimeException e) {
        log(e, "Waiting inbox poll failed; stopping this poll");
        return;
      }
    }
  }

  private static void log(Throwable failure, String message) {
    LOGGER
        .atError()
        .setCause(failure)
        .addKeyValue("event", "settlement.inbox")
        .addKeyValue("result", "ERROR")
        .addKeyValue("errorType", failure.getClass().getName())
        .log(message);
  }
}
