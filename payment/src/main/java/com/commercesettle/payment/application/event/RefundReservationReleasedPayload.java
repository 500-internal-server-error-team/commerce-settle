package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** Settlement가 외부 환불 확정 거절에 따라 정산금 예약을 해제했다는 확인. */
public record RefundReservationReleasedPayload(UUID refundId, UUID paymentId, UUID reservationId) {
  public RefundReservationReleasedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(reservationId, "reservationId");
  }
}
