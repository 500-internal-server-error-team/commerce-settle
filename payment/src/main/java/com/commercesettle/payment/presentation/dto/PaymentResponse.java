package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.PaymentView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** OpenAPI Payment. null 필드도 키를 생략하지 않는다. */
@Schema(name = "Payment", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID orderId,
    @Schema(
            allowableValues = {
              "PENDING",
              "PROCESSING",
              "CONFIRMED",
              "DECLINED",
              "UNKNOWN",
              "CANCELLED"
            })
        @JsonProperty(required = true)
        String status,
    @JsonProperty(required = true) long totalAmount,
    @JsonProperty(required = true) long mileageAmount,
    @JsonProperty(required = true) long externalAmount,
    @Schema(
            nullable = true,
            allowableValues = {"CREDIT_CARD", "DEBIT_CARD", "EASY_PAY"})
        @JsonProperty(required = true)
        String method,
    @Schema(
            nullable = true,
            allowableValues = {"PG_A", "PG_B", "PG_C", "EASY_PAY"})
        @JsonProperty(required = true)
        String providerId,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime confirmedAt,
    @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @JsonProperty(required = true) long platformCouponAmount,
    @JsonProperty(required = true) long settlementBaseAmount) {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public static PaymentResponse from(PaymentView view) {
    return new PaymentResponse(
        view.id(),
        view.orderId(),
        view.status().name(),
        view.totalAmount(),
        view.mileageAmount(),
        view.externalAmount(),
        view.method(),
        view.providerId(),
        kst(view.confirmedAt()),
        view.version(),
        kst(view.createdAt()),
        view.platformCouponAmount(),
        view.settlementBaseAmount());
  }

  private static OffsetDateTime kst(Instant value) {
    return value == null ? null : value.atZone(SEOUL).toOffsetDateTime();
  }
}
