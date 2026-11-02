package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.PaymentJobView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI Job. null 필드도 키를 생략하지 않는다. */
@Schema(name = "Job", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record JobResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID resourceId,
    @Schema(minLength = 1, maxLength = 40) @JsonProperty(required = true) String operation,
    @Schema(allowableValues = {"PENDING", "RUNNING", "RETRY", "REVIEW_REQUIRED", "DONE"})
        @JsonProperty(required = true)
        String status,
    @Schema(minimum = "0") @JsonProperty(required = true) int attempts,
    @JsonProperty(required = true) OffsetDateTime nextRunAt,
    @Schema(nullable = true, minLength = 1, maxLength = 80) @JsonProperty(required = true)
        String lastErrorCode,
    @Schema(minimum = "0") @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt) {

  public static JobResponse from(PaymentJobView view) {
    return new JobResponse(
        view.id(),
        view.resourceId(),
        view.operation(),
        view.status().name(),
        view.attempts(),
        KstTime.of(view.nextRunAt()),
        view.lastErrorCode(),
        view.version(),
        KstTime.of(view.createdAt()));
  }
}
