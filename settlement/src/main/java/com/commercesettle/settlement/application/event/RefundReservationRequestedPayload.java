package com.commercesettle.settlement.application.event;

import java.util.UUID;

/** OpenAPI {@code RefundReservationRequestedPayload}. Payment가 전액 환불의 판매자 몫 예약을 요청한다. */
public record RefundReservationRequestedPayload(UUID refundId, UUID paymentId, long netAmount) {}
