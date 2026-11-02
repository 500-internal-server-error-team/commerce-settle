package com.commercesettle.settlement.application.event;

import java.util.UUID;

/**
 * OpenAPI {@code RefundReservationResultPayload}. {@code reservationId}는 RESERVED일 때만, {@code
 * reasonCode}는 REJECTED일 때만 채운다. 둘 다 없으면 null로 보낸다(필수 nullable 필드).
 */
public record RefundReservationResultPayload(
    UUID refundId, UUID paymentId, Result result, UUID reservationId, String reasonCode) {
  public enum Result {
    RESERVED,
    REJECTED,
    WAITING
  }
}
