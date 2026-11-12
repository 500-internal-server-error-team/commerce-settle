package com.commercesettle.payment.application.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Commerce가 자동 구매확정과 함께 보낸 구매 적립 확정. OpenAPI MileageAccrualConfirmedPayload와 같은 4필드다. */
public record MileageAccrualConfirmedPayload(
    UUID paymentId, UUID orderId, long accrualAmount, OffsetDateTime confirmedAt) {
  public MileageAccrualConfirmedPayload {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(confirmedAt, "confirmedAt");
  }
}
