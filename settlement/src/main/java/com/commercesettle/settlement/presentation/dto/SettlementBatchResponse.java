package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI SettlementBatch. */
@Schema(name = "SettlementBatch", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SettlementBatchResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID sellerId,
    @JsonProperty(required = true) OffsetDateTime cutoffAt,
    @JsonProperty(required = true) long netAmount,
    @Schema(allowableValues = {"CREATED", "RESERVED", "PROCESSING", "COMPLETED", "REVIEW_REQUIRED"})
        @JsonProperty(required = true)
        String status,
    @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @Schema(description = "Asia/Seoul 마감 날짜") @JsonProperty(required = true) LocalDate batchDate,
    @JsonProperty(required = true) String policyVersion) {
  public static SettlementBatchResponse from(SettlementBatch batch) {
    return new SettlementBatchResponse(
        batch.getId(),
        batch.getSellerId(),
        Kst.of(batch.getCutoffAt()),
        batch.getNetAmount(),
        batch.getStatus().name(),
        batch.getVersion(),
        Kst.of(batch.getCreatedAt()),
        batch.getBatchDate(),
        batch.getPolicyVersion());
  }
}
