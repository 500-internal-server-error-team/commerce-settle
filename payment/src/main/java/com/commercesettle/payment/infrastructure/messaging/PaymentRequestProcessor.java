package com.commercesettle.payment.infrastructure.messaging;

import com.commercesettle.payment.application.service.ApplyOrderPaymentRequestService;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * WAITING 결제 요청을 주기적으로 처리한다. 한 건마다 별도 트랜잭션이며, 처리에 실패한 건은 서비스가 재시도 시각을 미뤄 기록하므로 다음 건을 이어 처리한다. 선점이나
 * 실패 기록 자체가 실패하면 이번 주기를 멈추고 다음 주기에 다시 시도한다.
 */
public class PaymentRequestProcessor {
  private final ApplyOrderPaymentRequestService service;
  private final int batchSize;

  public PaymentRequestProcessor(ApplyOrderPaymentRequestService service, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("Invalid payment request batch size");
    }
    this.service = service;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${app.payment-request-processor.interval-ms:1000}",
      fixedDelayString = "${app.payment-request-processor.interval-ms:1000}")
  public void run() {
    for (int processed = 0; processed < batchSize; processed++) {
      if (!service.processNext()) {
        return;
      }
    }
  }
}
