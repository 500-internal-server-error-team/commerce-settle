package com.commercesettle.settlement.application.event;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * OpenAPI {@code SettlementNoticeRequestedPayload}. businessRef는 지급 완료면 payoutId, 나머지는 batchId다. 지급
 * 완료만 succeededAt을, 조치 필요만 reasonCode를 채운다.
 */
public record SettlementNoticeRequestedPayload(
    Kind kind,
    UUID businessRef,
    UUID batchId,
    UUID payoutId,
    UUID sellerId,
    OffsetDateTime cutoffAt,
    long amount,
    OffsetDateTime succeededAt,
    String reasonCode,
    LocalDate batchDate,
    String policyVersion) {
  public enum Kind {
    SETTLEMENT_READY,
    PAYOUT_COMPLETED,
    PAYOUT_ACTION_REQUIRED
  }
}
