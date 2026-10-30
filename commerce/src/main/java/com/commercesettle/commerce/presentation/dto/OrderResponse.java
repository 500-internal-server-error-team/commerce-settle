package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.OrderView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
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
    @Schema(minLength = 1, maxLength = 120) @JsonProperty(required = true) String productName,
    @Schema(minimum = "1") @JsonProperty(required = true) int quantity,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long grossAmount,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long couponAmount,
    @Schema(minimum = "1", maximum = "10000000") @JsonProperty(required = true) long totalAmount,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long mileageAmount,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long externalAmount,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
        long sellerCouponAmount,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
        long platformCouponAmount,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
        long settlementBaseAmount,
    @Schema(nullable = true) @JsonProperty(required = true) UUID eventId,
    @Schema(nullable = true) @JsonProperty(required = true) UUID paymentId,
    @Schema(
            allowableValues = {
              "PAYMENT_PENDING",
              "PAID",
              "DECLINED",
              "CANCEL_PENDING",
              "CANCELLED",
              "REFUND_PENDING",
              "REFUNDED",
              "PURCHASE_CONFIRMED"
            })
        @JsonProperty(required = true)
        String status,
    @Schema(allowableValues = {"UNSHIPPED", "SHIPPED", "DELIVERED", "RETURN_RECEIVED"})
        @JsonProperty(required = true)
        String fulfillmentStatus,
    @Schema(minimum = "0") @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    // OpenAPI 3.1의 allowableValues는 문자열 enum이 되므로 정수 JSON 값을 지정한다.
    @Schema(
            extensions =
                @Extension(
                    properties =
                        @ExtensionProperty(name = "enum", value = "[2,3]", parseValue = true)))
        @JsonProperty(required = true)
        int deliveryBusinessDays,
    @Schema(minLength = 1, maxLength = 32) @JsonProperty(required = true)
        String deliveryCalendarVersion,
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
