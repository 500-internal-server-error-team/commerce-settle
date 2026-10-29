package com.commercesettle.commerce.application.dto;

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
    OrderAddressView address) {}
