package com.commercesettle.commerce.application.dto;

import com.commercesettle.commerce.domain.model.Order;
import java.time.Instant;
import java.util.UUID;

/** 생성 당시 금액과 배송 정책을 보관하는 주문 조회 사본. */
public record OrderView(
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
    Instant createdAt,
    int deliveryBusinessDays,
    String deliveryCalendarVersion,
    Instant paymentConfirmedAt,
    Instant shippedAt,
    Instant deliveryDueAt,
    Instant deliveredAt,
    Instant refundDeadlineAt,
    Instant purchaseConfirmedAt,
    OrderAddressView address) {
  /** 공개 주문 응답에는 배송지 원문을 포함하지 않는다. */
  public static OrderView from(Order order) {
    var amounts = order.getAmounts();
    return new OrderView(
        order.getId(),
        order.getBuyerId(),
        order.getSellerId(),
        order.getProductId(),
        order.getProductName(),
        order.getQuantity(),
        amounts.grossAmount(),
        amounts.couponAmount(),
        amounts.totalAmount(),
        amounts.mileageAmount(),
        amounts.externalAmount(),
        amounts.sellerCouponAmount(),
        amounts.platformCouponAmount(),
        amounts.settlementBaseAmount(),
        order.getEventId(),
        order.getPaymentId(),
        order.getStatus().name(),
        order.getFulfillmentStatus().name(),
        order.getVersion(),
        order.getCreatedAt(),
        order.getDelivery().getDeliveryBusinessDays(),
        order.getDelivery().getDeliveryCalendarVersion(),
        order.getPaymentConfirmedAt(),
        order.getShippedAt(),
        order.getDeliveryDueAt(),
        order.getDeliveredAt(),
        order.getRefundDeadlineAt(),
        order.getPurchaseConfirmedAt(),
        null);
  }
}
