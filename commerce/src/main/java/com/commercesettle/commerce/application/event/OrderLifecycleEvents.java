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

  /** 결제 완료를 반영하면서 보내는 구매 적립 예정. 주문 생성 때 고정한 정책 사본과 예정액이다. */
  public record MileageAccrualScheduled(
      UUID paymentId,
      UUID orderId,
      UUID buyerId,
      long baseAmount,
      int rateBasisPoints,
      long perOrderCap,
      long accrualAmount,
      String accrualPolicyVersion,
      OffsetDateTime paymentConfirmedAt) {}

  /** 자동 구매확정과 함께 보내는 구매 적립 확정. Payment가 예정을 사용 가능으로 바꾼다. */
  public record MileageAccrualConfirmed(
      UUID paymentId, UUID orderId, long accrualAmount, OffsetDateTime confirmedAt) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record PurchaseConfirmed(
      UUID paymentId,
      UUID orderId,
      long orderVersion,
      OffsetDateTime confirmedAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime refundDeadlineAt,
      UUID demoRunId,
      Integer demoRefundSeconds) {}
}
