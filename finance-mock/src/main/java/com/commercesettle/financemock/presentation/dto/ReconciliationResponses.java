package com.commercesettle.financemock.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReconciliationResponses {
  private ReconciliationResponses() {}

  public record Payout(
      UUID id,
      UUID businessKey,
      long amount,
      String status,
      String fundingAccountRef,
      OffsetDateTime createdAt,
      OffsetDateTime succeededAt,
      long debitAmount,
      long debitCount) {}

  public record Transaction(
      UUID id,
      String providerId,
      String operation,
      UUID businessKey,
      UUID originalId,
      long amount,
      String status,
      OffsetDateTime createdAt,
      OffsetDateTime succeededAt) {}

  public record PayoutPage(
      List<Payout> items,
      int page,
      int size,
      long totalElements,
      long totalPages,
      OffsetDateTime cutoffAt,
      OffsetDateTime snapshotAt,
      String nextCursor) {}

  public record PaymentPage(
      List<Transaction> items,
      int page,
      int size,
      long totalElements,
      long totalPages,
      OffsetDateTime cutoffAt,
      OffsetDateTime snapshotAt,
      String nextCursor) {}

  public record Integrity(OffsetDateTime checkedAt, Map<String, Long> checks) {}
}
