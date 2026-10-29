package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.OrderView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Schema(name = "Order", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OrderResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID buyerId,
    @JsonProperty(required = true) UUID sellerId,
    @JsonProperty(required = true) UUID productId,
    @JsonProperty(required = true) String productName,
    @JsonProperty(required = true) int quantity,
    @JsonProperty(required = true) long grossAmount,
    @JsonProperty(required = true) long couponAmount,
    @JsonProperty(required = true) long totalAmount,
    @JsonProperty(required = true) long mileageAmount,
    @JsonProperty(required = true) long externalAmount,
    @JsonProperty(required = true) long sellerCouponAmount,
    @JsonProperty(required = true) long platformCouponAmount,
    @JsonProperty(required = true) long settlementBaseAmount,
    @Schema(nullable = true) @JsonProperty(required = true) UUID eventId,
    @Schema(nullable = true) @JsonProperty(required = true) UUID paymentId,
    @JsonProperty(required = true) String status,
    @JsonProperty(required = true) String fulfillmentStatus,
    @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @JsonProperty(required = true) int deliveryBusinessDays,
    @JsonProperty(required = true) String deliveryCalendarVersion,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime paymentConfirmedAt,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime shippedAt,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime deliveryDueAt,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime deliveredAt,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime refundDeadlineAt,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime purchaseConfirmedAt) {
  public static OrderResponse from(OrderView o) {
    return new OrderResponse(
        o.id(),
        o.buyerId(),
        o.sellerId(),
        o.productId(),
        o.productName(),
        o.quantity(),
        o.grossAmount(),
        o.couponAmount(),
        o.totalAmount(),
        o.mileageAmount(),
        o.externalAmount(),
        o.sellerCouponAmount(),
        o.platformCouponAmount(),
        o.settlementBaseAmount(),
        o.eventId(),
        o.paymentId(),
        o.status(),
        o.fulfillmentStatus(),
        o.version(),
        kst(o.createdAt()),
        o.deliveryBusinessDays(),
        o.deliveryCalendarVersion(),
        kst(o.paymentConfirmedAt()),
        kst(o.shippedAt()),
        kst(o.deliveryDueAt()),
        kst(o.deliveredAt()),
        kst(o.refundDeadlineAt()),
        kst(o.purchaseConfirmedAt()));
  }

  private static OffsetDateTime kst(Instant value) {
    return value == null ? null : value.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
  }
}
