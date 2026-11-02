package com.commercesettle.settlement.application.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI {@code RefundFinancialCompletedPayload}. 외부 환불·마일리지 복원이 끝났으며 금액은 원거래 금액이다. */
public record RefundFinancialCompletedPayload(
    UUID refundId,
    UUID paymentId,
    long externalAmount,
    long mileageAmount,
    OffsetDateTime restoredAt) {}
