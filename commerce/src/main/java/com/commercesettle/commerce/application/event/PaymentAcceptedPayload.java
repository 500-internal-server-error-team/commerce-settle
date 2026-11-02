package com.commercesettle.commerce.application.event;

import java.util.Objects;
import java.util.UUID;

/** PaymentAccepted v1의 접수 ID 연결 계약. 결제 확정을 뜻하지 않는다. */
public record PaymentAcceptedPayload(
    UUID paymentId, UUID orderId, long orderVersion, UUID paymentCycleId) {
  public PaymentAcceptedPayload {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(paymentCycleId, "paymentCycleId");
    if (orderVersion < 0) throw new IllegalArgumentException("Invalid order version");
  }
}
