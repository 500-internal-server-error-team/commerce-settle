package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/**
 * 금융 환불 실패. 정산금 예약이 거절됐거나 외부 환불 확정 거절 뒤 예약 해제가 확인된 경우에만 보내므로 reservationReleased는 항상 true다. 결과 불명은
 * 실패로 보내지 않는다.
 */
public record RefundFailedPayload(
    UUID refundId, UUID paymentId, UUID orderId, String reasonCode, boolean reservationReleased)
    implements RefundEventPayload {
  public RefundFailedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 80) {
      throw new IllegalArgumentException("reasonCode");
    }
    if (!reservationReleased) {
      throw new IllegalArgumentException("reservationReleased");
    }
  }
}
