package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** Settlement에 지급과 겹치지 않도록 판매자 순액(N)의 정산금 예약을 요청한다. */
public record RefundReservationRequestedPayload(UUID refundId, UUID paymentId, long netAmount)
    implements RefundEventPayload {
  public RefundReservationRequestedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    if (netAmount < 0) {
      throw new IllegalArgumentException("netAmount");
    }
  }
}
