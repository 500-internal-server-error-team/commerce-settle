package com.commercesettle.settlement.application.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI {@code RefundLedgerReversedPayload}. 원장 상쇄와 항목 환불 완료를 커밋한 시각을 싣는다. */
public record RefundLedgerReversedPayload(
    UUID refundId, UUID paymentId, UUID reservationId, OffsetDateTime reversedAt) {}
