package com.commercesettle.commerce.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 동적 ProblemDetail 확장 필드의 OpenAPI 계약. 실제 응답 직렬화는 Spring ProblemDetail이 담당한다. */
@Schema(name = "Problem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ProblemResponse(
    @Schema(format = "uri-reference") @JsonProperty(required = true) String type,
    @Schema(minLength = 1, maxLength = 200) @JsonProperty(required = true) String title,
    @Schema(minimum = "400", maximum = "599") @JsonProperty(required = true) int status,
    @Schema(minLength = 1, maxLength = 1000) @JsonProperty(required = true) String detail,
    @Schema(format = "uri-reference") @JsonProperty(required = true) String instance,
    @Schema(minLength = 1, maxLength = 80) @JsonProperty(required = true) String code,
    @Schema(minLength = 1, maxLength = 64) @JsonProperty(required = true) String traceId,
    List<FieldError> errors) {
  @Schema(name = "FieldError", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record FieldError(
      @Schema(minLength = 1, maxLength = 100) @JsonProperty(required = true) String field,
      @Schema(minLength = 1, maxLength = 200) @JsonProperty(required = true) String reason) {}
}
