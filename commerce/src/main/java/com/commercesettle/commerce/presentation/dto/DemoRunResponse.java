package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.DemoRunView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Schema(name = "DemoRun", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DemoRunResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID sellerId,
    @JsonProperty(required = true) UUID productId,
    @JsonProperty(required = true) @Schema(nullable = true) UUID eventId,
    @JsonProperty(required = true) int deliverySeconds,
    @JsonProperty(required = true) int refundSeconds,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @JsonProperty(required = true) @Schema(nullable = true) OffsetDateTime frozenAt,
    @JsonProperty(required = true) List<UUID> orderIds,
    @JsonProperty(required = true) List<UUID> payableOrderIds,
    @JsonProperty(required = true) List<UUID> refundedOrderIds,
    @JsonProperty(required = true) int pendingCount,
    @JsonProperty(required = true) int excludedCount) {
  public static DemoRunResponse from(DemoRunView v) {
    var zone = ZoneId.of("Asia/Seoul");
    return new DemoRunResponse(
        v.id(),
        v.sellerId(),
        v.productId(),
        v.eventId(),
        v.deliverySeconds(),
        v.refundSeconds(),
        v.createdAt().atZone(zone).toOffsetDateTime(),
        v.frozenAt() == null ? null : v.frozenAt().atZone(zone).toOffsetDateTime(),
        v.orderIds(),
        v.payableOrderIds(),
        v.refundedOrderIds(),
        v.pendingCount(),
        v.excludedCount());
  }
}
