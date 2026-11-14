package com.commercesettle.settlement.application.event;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI {@code PurchaseConfirmedPayload}. 배송완료와 환불 마감 이후의 확정임을 시각으로 보존한다. */
public record PurchaseConfirmedPayload(
    UUID paymentId,
    UUID orderId,
    long orderVersion,
    OffsetDateTime confirmedAt,
    OffsetDateTime deliveredAt,
    OffsetDateTime refundDeadlineAt,
    UUID demoRunId,
    Integer demoRefundSeconds) {

  private static final Duration REFUND_WINDOW = Duration.ofHours(168);

  /** 일반 구매확정은 시연 정보 없이 기존 168시간 계약을 사용한다. */
  public PurchaseConfirmedPayload(
      UUID paymentId,
      UUID orderId,
      long orderVersion,
      OffsetDateTime confirmedAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime refundDeadlineAt) {
    this(paymentId, orderId, orderVersion, confirmedAt, deliveredAt, refundDeadlineAt, null, null);
  }

  public boolean timesMatchContract() {
    return timesMatchContract(false);
  }

  /** 단축 기한은 활성화된 전용 환경에서 완전한 회차 사본이 있을 때만 허용한다. */
  public boolean timesMatchContract(boolean demoEnabled) {
    Duration window = REFUND_WINDOW;
    if (demoRunId != null || demoRefundSeconds != null) {
      if (!demoEnabled
          || demoRunId == null
          || demoRefundSeconds == null
          || demoRefundSeconds < 1
          || demoRefundSeconds > 3600) return false;
      window = Duration.ofSeconds(demoRefundSeconds);
    }
    return deliveredAt.toInstant().plus(window).equals(refundDeadlineAt.toInstant())
        && confirmedAt.toInstant().isAfter(refundDeadlineAt.toInstant());
  }
}
