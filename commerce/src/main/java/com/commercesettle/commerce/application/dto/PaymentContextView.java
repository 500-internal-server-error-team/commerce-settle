package com.commercesettle.commerce.application.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentContextView(
    long grossAmount,
    long couponAmount,
    long totalAmount,
    long mileageAmount,
    long externalAmount,
    UUID orderId,
    UUID buyerId,
    UUID sellerId,
    long orderVersion,
    UUID paymentCycleId,
    UUID productId,
    String status,
    String policyVersion,
    Instant paymentWaitUntil,
    long sellerCouponAmount,
    long platformCouponAmount,
    long settlementBaseAmount) {}
