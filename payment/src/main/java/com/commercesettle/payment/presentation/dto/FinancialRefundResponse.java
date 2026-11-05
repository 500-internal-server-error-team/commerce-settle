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
  @Schema(
      name = "FinancialRefundSummary",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Summary(
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
      @JsonProperty(required = true) long version,
      @JsonProperty(required = true) OffsetDateTime createdAt) {
    public static Summary from(
        com.commercesettle.payment.application.service.RefundQueryService.Summary v) {
      return new Summary(
          v.id(),
          v.paymentId(),
          v.externalAmount(),
          v.mileageAmount(),
          v.status(),
          v.version(),
          v.createdAt().atZone(SEOUL).toOffsetDateTime());
    }
  }

  @Schema(
      name = "FinancialRefundPage",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Page(
      @JsonProperty(required = true) java.util.List<Summary> items,
      @Schema(minimum = "0") @JsonProperty(required = true) int page,
      @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
      @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
      @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
    public static Page from(
        com.commercesettle.payment.application.service.RefundQueryService.Page v) {
      return new Page(
          v.items().stream().map(Summary::from).toList(),
          v.page(),
          v.size(),
          v.totalElements(),
          v.totalPages());
    }
  }

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
