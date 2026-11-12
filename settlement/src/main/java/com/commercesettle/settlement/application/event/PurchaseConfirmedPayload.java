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
    OffsetDateTime refundDeadlineAt) {

  private static final Duration REFUND_WINDOW = Duration.ofHours(168);

  /** 시각 계약: deliveredAt+168시간=refundDeadlineAt이고 confirmedAt은 마감 정각을 지난 뒤다. 정각은 환불 가능 시각이다. */
  public boolean timesMatchContract() {
    return deliveredAt.toInstant().plus(REFUND_WINDOW).equals(refundDeadlineAt.toInstant())
        && confirmedAt.toInstant().isAfter(refundDeadlineAt.toInstant());
  }
}
