package com.commercesettle.settlement.application.event;

import java.util.UUID;

/** OpenAPI {@code RefundReservationReleasedPayload}. 예약을 풀고 항목을 예약 직전 상태로 되돌렸다. */
public record RefundReservationReleasedPayload(UUID refundId, UUID paymentId, UUID reservationId) {}
