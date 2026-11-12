package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI SettlementItem. null 필드도 키를 생략하지 않는다. */
@Schema(name = "SettlementItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SettlementItemResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID paymentId,
    @JsonProperty(required = true) UUID orderId,
    @JsonProperty(required = true) UUID productId,
    @JsonProperty(required = true) long totalAmount,
    @JsonProperty(required = true) long externalAmount,
    @JsonProperty(required = true) long mileageAmount,
    @JsonProperty(required = true) long feeAmount,
    @JsonProperty(required = true) long netAmount,
    @Schema(
            allowableValues = {
              "WAITING_CONFIRMATION",
              "PAYABLE",
              "REFUND_RESERVED",
              "PAYOUT_RESERVED",
              "REFUNDED",
              "PAID"
            })
        @JsonProperty(required = true)
        String status,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime purchaseConfirmedAt,
    @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @JsonProperty(required = true) long platformCouponAmount,
    @JsonProperty(required = true) long settlementBaseAmount,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime readyAt,
    @Schema(nullable = true) @JsonProperty(required = true) UUID payoutId) {
  public static SettlementItemResponse from(SettlementItem item) {
    SettlementAmounts amounts = item.getAmounts();
    return new SettlementItemResponse(
        item.getId(),
        item.getPaymentId(),
        item.getOrderId(),
        item.getProductId(),
        amounts.totalAmount(),
        amounts.externalAmount(),
        amounts.mileageAmount(),
        amounts.feeAmount(),
        amounts.netAmount(),
        item.getStatus().name(),
        Kst.of(item.getPurchaseConfirmedAt()),
        item.getVersion(),
        Kst.of(item.getCreatedAt()),
        amounts.platformCouponAmount(),
        amounts.settlementBaseAmount(),
        Kst.of(item.getReadyAt()),
        item.getPayoutId());
  }
}
