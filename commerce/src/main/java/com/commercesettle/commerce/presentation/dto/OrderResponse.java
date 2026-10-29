package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.OrderView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Schema(name = "Order")
public record OrderResponse(
    UUID id,
    UUID buyerId,
    UUID sellerId,
    UUID productId,
    String productName,
    int quantity,
    long grossAmount,
    long couponAmount,
    long totalAmount,
    long mileageAmount,
    long externalAmount,
    long sellerCouponAmount,
    long platformCouponAmount,
    long settlementBaseAmount,
    UUID eventId,
    UUID paymentId,
    String status,
    String fulfillmentStatus,
    long version,
    OffsetDateTime createdAt,
    int deliveryBusinessDays,
    String deliveryCalendarVersion,
    OffsetDateTime paymentConfirmedAt,
    OffsetDateTime shippedAt,
    OffsetDateTime deliveryDueAt,
    OffsetDateTime deliveredAt,
    OffsetDateTime refundDeadlineAt,
    OffsetDateTime purchaseConfirmedAt) {
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
