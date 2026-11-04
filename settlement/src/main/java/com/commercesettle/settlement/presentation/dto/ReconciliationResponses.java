package com.commercesettle.settlement.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 고정 HTTP 계약. 원천별 추가 증빙만 JSON으로 보존한다. */
public final class ReconciliationResponses {
  private ReconciliationResponses() {}

  public record Run(
      UUID id,
      OffsetDateTime cutoffAt,
      OffsetDateTime snapshotAt,
      String stage,
      String status,
      String cursor,
      Long expectedTotal,
      long captured,
      long attempts,
      String lastError,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      OffsetDateTime completedAt) {}

  public record Source(
      UUID eventId,
      String eventType,
      OffsetDateTime occurredAt,
      OffsetDateTime createdAt,
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
      OffsetDateTime publishedAt,
      UUID businessKey,
      long amount,
      String status,
      OffsetDateTime succeededAt,
      long debitAmount,
      long debitCount,
      String fundingAccountRef) {}

  public record Result(
      UUID eventId,
      Source body,
      String status,
      String reason,
      long difference,
      JsonNode evidence,
      OffsetDateTime firstSeenAt,
      OffsetDateTime checkedAt) {}

  public record Check(
      String checkKey,
      String status,
      String reason,
      long count,
      long difference,
      JsonNode evidence) {}

  public record Results(
      List<Result> items,
      int page,
      int size,
      long totalElements,
      long totalPages,
      List<Check> checks) {}

  public record History(
      long id,
      UUID eventId,
      String status,
      String reason,
      JsonNode evidence,
      OffsetDateTime createdAt) {}
}
