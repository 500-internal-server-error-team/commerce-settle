package com.commercesettle.payment.application.event;

import java.util.Objects;
import java.util.UUID;

/** 금융 환불 접수. Commerce가 취소·반품 요청에 환불 ID를 연결한다. */
public record RefundAcceptedPayload(UUID refundId, UUID paymentId, UUID orderId, UUID requestId)
    implements RefundEventPayload {
  public RefundAcceptedPayload {
    Objects.requireNonNull(refundId, "refundId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(requestId, "requestId");
  }
}
