package com.commercesettle.commerce.application.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI v1 payload. 주문 집계 버전과 결제 집계 버전은 비교하지 않는다. */
public final class OrderLifecycleEvents {
  private OrderLifecycleEvents() {}

  public record CancellationRequested(
      UUID orderId, long orderVersion, UUID paymentCycleId, UUID requestId) {}

  public record RefundApproved(
      UUID requestId,
      UUID orderId,
      UUID paymentId,
      OffsetDateTime acceptedAt,
      OffsetDateTime approvedAt,
      long orderVersion) {}

  public record RefundOrderRestored(
      UUID refundId, UUID paymentId, UUID orderId, long orderVersion) {}

  public record PurchaseConfirmed(
      UUID paymentId,
      UUID orderId,
      long orderVersion,
      OffsetDateTime confirmedAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime refundDeadlineAt) {}
}
