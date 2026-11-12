package com.commercesettle.payment.application.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Commerce가 취소·반품을 승인해 원결제 전액 환불을 요청한 이벤트 본문. OpenAPI RefundApprovedPayload와 같은 6필드다. */
public record RefundApprovedPayload(
    UUID requestId,
    UUID orderId,
    UUID paymentId,
    OffsetDateTime acceptedAt,
    OffsetDateTime approvedAt,
    long orderVersion) {
  public RefundApprovedPayload {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(acceptedAt, "acceptedAt");
    Objects.requireNonNull(approvedAt, "approvedAt");
    if (orderVersion < 0) {
      throw new IllegalArgumentException("orderVersion");
    }
  }
}
