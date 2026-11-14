package com.commercesettle.commerce.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DemoCapabilities", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DemoCapabilitiesResponse(@JsonProperty(required = true) boolean enabled) {}
