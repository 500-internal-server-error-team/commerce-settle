package com.commercesettle.payment.application.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** 외부 환불(E)과 마일리지 복원(M)을 끝냈다. Settlement가 최초 원장을 원금액으로 상쇄한다. */
public record RefundFinancialCompletedPayload(
    UUID refundId,
    UUID paymentId,
    long externalAmount,
    long mileageAmount,
    OffsetDateTime restoredAt)
    implements RefundEventPayload {
  public RefundFinancialCompletedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(restoredAt, "restoredAt");
    if (externalAmount < 0 || mileageAmount < 0) {
      throw new IllegalArgumentException("amount");
    }
  }
}
