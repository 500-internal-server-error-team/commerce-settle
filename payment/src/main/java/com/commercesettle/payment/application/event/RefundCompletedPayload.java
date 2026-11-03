package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/**
 * 외부 환불·마일리지 복원·원장 상쇄 뒤 Commerce에 주문 복구를 요청한다. 이때 금융 환불은 INTERNAL_PENDING이며, RefundOrderRestored를
 * 반영해야 완료된다.
 */
public record RefundCompletedPayload(UUID refundId, UUID paymentId, UUID orderId)
    implements RefundEventPayload {
  public RefundCompletedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
  }
}
