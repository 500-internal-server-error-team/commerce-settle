package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** Commerce가 미결제 주문 취소를 요청한 이벤트 본문. */
public record PaymentCancellationRequestedPayload(
    UUID orderId, long orderVersion, UUID paymentCycleId, UUID requestId) {
  public PaymentCancellationRequestedPayload {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(paymentCycleId, "paymentCycleId");
    Objects.requireNonNull(requestId, "requestId");
    if (orderVersion < 0) {
      throw new IllegalArgumentException("orderVersion");
    }
  }
}
