package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.PaymentAttemptView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(name = "PaymentAttempt", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PaymentAttemptResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID paymentId,
    @Schema(minimum = "1") @JsonProperty(required = true) int attemptNo,
    @Schema(allowableValues = {"PG_A", "PG_B", "PG_C", "EASY_PAY"}) @JsonProperty(required = true)
        String providerId,
    @Schema(allowableValues = {"CREDIT_CARD", "DEBIT_CARD", "EASY_PAY"})
        @JsonProperty(required = true)
        String method,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long amount,
    @Schema(
            allowableValues = {
              "PENDING",
              "PROCESSING",
              "UNKNOWN",
              "APPROVED",
              "DECLINED",
              "CANCELLED"
            })
        @JsonProperty(required = true)
        String status,
    @JsonProperty(required = true) OffsetDateTime createdAt) {

  public static PaymentAttemptResponse from(PaymentAttemptView view) {
    return new PaymentAttemptResponse(
        view.id(),
        view.paymentId(),
        view.attemptNo(),
        view.providerId(),
        view.method(),
        view.amount(),
        view.status().name(),
        KstTime.of(view.createdAt()));
  }
}
