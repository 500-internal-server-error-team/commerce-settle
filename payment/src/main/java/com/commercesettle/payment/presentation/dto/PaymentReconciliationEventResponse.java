package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.port.ReconciliationEventReader.ReconciliationEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI PaymentReconciliationEvent. 발행 위치 미확정이면 partition·offset·publishedAt이 함께 null이다. */
@Schema(
    name = "PaymentReconciliationEvent",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentReconciliationEventResponse(
    @JsonProperty(required = true) UUID eventId,
    @Schema(allowableValues = {"PaymentConfirmed", "RefundFinancialCompleted"})
        @JsonProperty(required = true)
        String eventType,
    @JsonProperty(required = true) OffsetDateTime occurredAt,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @JsonProperty(required = true) UUID paymentId,
    @Schema(nullable = true) @JsonProperty(required = true) UUID refundId,
    @Schema(minimum = "0") @JsonProperty(required = true) long externalAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long mileageAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long platformCouponAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long settlementBaseAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long feeAmount,
    @Schema(minimum = "0") @JsonProperty(required = true) long sellerNetAmount,
    @Schema(allowableValues = {"PENDING", "PUBLISHED"}) @JsonProperty(required = true)
        String publicationState,
    @Schema(minLength = 1, maxLength = 100) @JsonProperty(required = true) String topic,
    @Schema(nullable = true, minimum = "0") @JsonProperty(required = true) Integer partition,
    @Schema(nullable = true, minimum = "0") @JsonProperty(required = true) Long offset,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime publishedAt) {

  public static PaymentReconciliationEventResponse from(ReconciliationEvent event) {
    return new PaymentReconciliationEventResponse(
        event.eventId(),
        event.eventType(),
        KstTime.of(event.occurredAt()),
        KstTime.of(event.createdAt()),
        event.paymentId(),
        event.refundId(),
        event.externalAmount(),
        event.mileageAmount(),
        event.platformCouponAmount(),
        event.settlementBaseAmount(),
        event.feeAmount(),
        event.sellerNetAmount(),
        event.published() ? "PUBLISHED" : "PENDING",
        event.topic(),
        event.partition(),
        event.offset(),
        KstTime.of(event.publishedAt()));
  }
}
