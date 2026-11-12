package com.commercesettle.commerce.infrastructure.messaging;

import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 한 건씩 반영·롤백·재시도 기록 뒤 다음 도래 건을 처리한다. */
public class PaymentAcceptedProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(PaymentAcceptedProcessor.class);
  private final PaymentAcceptedService service;
  private final int batchSize;

  public PaymentAcceptedProcessor(PaymentAcceptedService service, int batchSize) {
    if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid batch size");
    this.service = service;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${app.payment-accepted-processor.interval-ms:1000}",
      fixedDelayString = "${app.payment-accepted-processor.interval-ms:1000}")
  public void run() {
    try {
      for (int i = 0; i < batchSize; i++) {
        if (!service.processNext()) return;
      }
    } catch (RuntimeException exception) {
      LOG.warn("Payment acceptance processing paused: errorCode=INBOX_UNAVAILABLE");
    }
  }
}
