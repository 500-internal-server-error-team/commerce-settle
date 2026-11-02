package com.commercesettle.payment.application.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Settlement가 환불의 최초 원장을 원금액으로 상쇄했다는 확인. */
public record RefundLedgerReversedPayload(
    UUID refundId, UUID paymentId, UUID reservationId, OffsetDateTime reversedAt) {
  public RefundLedgerReversedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(reversedAt, "reversedAt");
  }
}
