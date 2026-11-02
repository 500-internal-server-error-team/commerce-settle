package com.commercesettle.settlement.application.event;

import java.util.UUID;

/** OpenAPI {@code RefundReleaseRequestedPayload}. 환불이 실패해 예약을 풀어 달라는 보상 요청이다. */
public record RefundReleaseRequestedPayload(UUID refundId, UUID paymentId, UUID reservationId) {}
