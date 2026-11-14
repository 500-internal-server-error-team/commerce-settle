package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.DemoStageView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "DemoStage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DemoStageResponse(
    @JsonProperty(required = true) @Schema(nullable = true) UUID nextCursor,
    @Schema(minimum = "0", maximum = "50") @JsonProperty(required = true) int processedCount,
    @Schema(minimum = "0", maximum = "50") @JsonProperty(required = true) int waitingCount) {
  public static DemoStageResponse from(DemoStageView v) {
    return new DemoStageResponse(v.nextCursor(), v.processedCount(), v.waitingCount());
  }
}
