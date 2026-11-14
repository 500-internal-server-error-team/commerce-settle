package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.port.DemoSettlementStore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "DemoSettlementBatch", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DemoSettlementBatchResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) long netAmount,
    @JsonProperty(required = true) String status,
    @Schema(nullable = true) @JsonProperty(required = true) UUID payoutId,
    @Schema(nullable = true) @JsonProperty(required = true) String payoutStatus) {
  public static DemoSettlementBatchResponse from(DemoSettlementStore.Batch b) {
    return new DemoSettlementBatchResponse(
        b.id(), b.netAmount(), b.status(), b.payoutId(), b.payoutStatus());
  }
}
