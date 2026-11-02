package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 정산 대조용 불변 금융 이벤트를 Outbox 원천에서 읽는다. 발행 여부와 무관하게 읽으며, 금액은 원천 이벤트 본문 그대로다. 정렬은 createdAt DESC,
 * eventId DESC다.
 */
public interface ReconciliationEventReader {
  /** 기준선 시각으로 쓸 DB 현재 시각. Outbox 저장 시각과 같은 시계다. */
  Instant now();

  long count(Instant cutoffAt, Instant snapshotAt);

  /**
   * @param after 직전 페이지의 마지막 위치. 첫 페이지는 null
   */
  List<ReconciliationEvent> read(Instant cutoffAt, Instant snapshotAt, Position after, int limit);

  record Position(Instant createdAt, UUID eventId) {}

  record ReconciliationEvent(
      UUID eventId,
      String eventType,
      Instant occurredAt,
      Instant createdAt,
      UUID paymentId,
      UUID refundId,
      long externalAmount,
      long mileageAmount,
      long platformCouponAmount,
      long settlementBaseAmount,
      long feeAmount,
      long sellerNetAmount,
      boolean published,
      String topic,
      Integer partition,
      Long offset,
      Instant publishedAt) {
    public Position position() {
      return new Position(createdAt, eventId);
    }
  }
}
