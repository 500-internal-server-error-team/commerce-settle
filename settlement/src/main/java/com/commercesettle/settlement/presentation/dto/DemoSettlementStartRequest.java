package com.commercesettle.settlement.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(name = "DemoSettlementStart", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DemoSettlementStartRequest(@NotNull @JsonProperty(required = true) UUID runId) {}
