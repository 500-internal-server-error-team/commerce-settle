package com.commercesettle.settlement.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 소유 서비스의 읽기 계약. 조회 실패·증빙 누락은 예외이며 빈 성공으로 대체하지 않는다. */
public interface ReconciliationSources {
  Page payments(Instant cutoff, Instant snapshot, String cursor);

  Page payouts(Instant cutoff, Instant snapshot, String cursor);

  record Page(
      Instant cutoffAt,
      Instant snapshotAt,
      long totalElements,
      String nextCursor,
      List<Event> items) {}

  record Event(
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
      String publicationState,
      String topic,
      Integer partition,
      Long offset,
      Instant publishedAt,
      UUID businessKey,
      long amount,
      String status,
      Instant succeededAt,
      long debitAmount,
      long debitCount,
      String fundingAccountRef) {}
}
