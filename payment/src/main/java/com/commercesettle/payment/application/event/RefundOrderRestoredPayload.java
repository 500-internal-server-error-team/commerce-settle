package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** Commerce가 RefundCompleted를 받아 주문·쿠폰·재고 복구를 저장했다는 확인. 이것을 반영해야 금융 환불이 COMPLETED가 된다. */
public record RefundOrderRestoredPayload(
    UUID refundId, UUID paymentId, UUID orderId, long orderVersion) {
  public RefundOrderRestoredPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    if (orderVersion < 0) {
      throw new IllegalArgumentException("orderVersion");
    }
  }
}
