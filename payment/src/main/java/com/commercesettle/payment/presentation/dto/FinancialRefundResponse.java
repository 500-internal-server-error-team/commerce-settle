package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.RefundView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** OpenAPI FinancialRefund. 시각은 Asia/Seoul 오프셋으로 반환한다. */
@Schema(name = "FinancialRefund", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record FinancialRefundResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID paymentId,
    @JsonProperty(required = true) long externalAmount,
    @JsonProperty(required = true) long mileageAmount,
    @Schema(
            allowableValues = {
              "RESERVING",
              "EXTERNAL_PENDING",
              "RESTORING",
              "INTERNAL_PENDING",
              "COMPLETED",
              "COMPENSATING",
              "DECLINED",
              "UNKNOWN"
            })
        @JsonProperty(required = true)
        String status,
    @Schema(minLength = 1, maxLength = 40) @JsonProperty(required = true) String sagaStep,
    @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt) {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public static FinancialRefundResponse from(RefundView view) {
    return new FinancialRefundResponse(
        view.id(),
        view.paymentId(),
        view.externalAmount(),
        view.mileageAmount(),
        view.status().name(),
        view.sagaStep().name(),
        view.version(),
        view.createdAt().atZone(SEOUL).toOffsetDateTime());
  }
}
