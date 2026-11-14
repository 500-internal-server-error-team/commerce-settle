package com.commercesettle.commerce.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(name = "DemoRunOptions", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DemoRunOptionsRequest(
    @NotNull @JsonProperty(required = true) UUID sellerId,
    @NotNull @JsonProperty(required = true) UUID productId,
    @Schema(nullable = true) UUID eventId,
    @Min(1) @Max(3600) @JsonProperty(required = true) int deliverySeconds,
    @Min(1) @Max(3600) @JsonProperty(required = true) int refundSeconds) {}
