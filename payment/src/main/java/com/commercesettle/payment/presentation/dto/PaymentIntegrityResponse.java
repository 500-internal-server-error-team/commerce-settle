package com.commercesettle.payment.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record PaymentIntegrityResponse(
    List<Result> items,
    int page,
    int size,
    long totalElements,
    long totalPages,
    OffsetDateTime cutoffAt,
    OffsetDateTime snapshotAt,
    String nextCursor,
    String direction,
    @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime checkedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> currentChecks) {
  public record Result(String key, String status, String reason, long difference) {}
}
