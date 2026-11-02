package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** 외부 승인 확정 거절 이벤트 본문. */
public record PaymentDeclinedPayload(
    UUID paymentId, UUID orderId, UUID paymentCycleId, String reasonCode) {
  public PaymentDeclinedPayload {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(paymentCycleId, "paymentCycleId");
    Objects.requireNonNull(reasonCode, "reasonCode");
  }
}
