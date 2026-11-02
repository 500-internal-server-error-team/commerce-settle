package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** 외부 환불이 확정 거절돼 Settlement에 정산금 예약 해제를 요청한다. */
public record RefundReleaseRequestedPayload(UUID refundId, UUID paymentId, UUID reservationId)
    implements RefundEventPayload {
  public RefundReleaseRequestedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(reservationId, "reservationId");
  }
}
