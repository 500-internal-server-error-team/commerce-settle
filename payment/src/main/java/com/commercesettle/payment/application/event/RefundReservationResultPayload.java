package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** Settlement의 정산금 예약 결과. RESERVED면 예약 ID가 있고, REJECTED면 사유가 있다. WAITING은 선행 원장을 기다리는 중이다. */
public record RefundReservationResultPayload(
    UUID refundId, UUID paymentId, Result result, UUID reservationId, String reasonCode) {
  public RefundReservationResultPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(result, "result");
    if (result == Result.RESERVED && reservationId == null) {
      throw new IllegalArgumentException("reservationId");
    }
  }

  public enum Result {
    RESERVED,
    REJECTED,
    WAITING
  }
}
