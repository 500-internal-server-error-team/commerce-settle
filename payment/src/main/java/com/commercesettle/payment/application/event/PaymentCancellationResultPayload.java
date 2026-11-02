package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** PaymentCancelled·PaymentCancellationRejected의 공통 본문. 두 이벤트는 같은 필드를 쓴다. */
public record PaymentCancellationResultPayload(
    UUID paymentId, UUID orderId, UUID paymentCycleId, String reasonCode) {
  public PaymentCancellationResultPayload {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(paymentCycleId, "paymentCycleId");
    Objects.requireNonNull(reasonCode, "reasonCode");
  }
}
