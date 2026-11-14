package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.port.DemoSettlementStore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Schema(name = "DemoSettlementRun", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DemoSettlementResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) int deliverySeconds,
    @JsonProperty(required = true) int refundSeconds,
    @JsonProperty(required = true) OffsetDateTime cutoffAt,
    @JsonProperty(required = true) String batchStatus,
    @JsonProperty(required = true) int itemCount,
    @JsonProperty(required = true) int refundedCount,
    @JsonProperty(required = true) List<DemoSettlementBatchResponse> batches) {
  public static DemoSettlementResponse from(
      DemoSettlementStore.Run run, List<DemoSettlementStore.Batch> batches) {
    return new DemoSettlementResponse(
        run.id(),
        run.deliverySeconds(),
        run.refundSeconds(),
        run.cutoffAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
        run.batchStatus(),
        run.itemIds().size(),
        run.refundedCount(),
        batches.stream().map(DemoSettlementBatchResponse::from).toList());
  }
}
