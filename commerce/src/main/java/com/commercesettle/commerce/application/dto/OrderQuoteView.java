package com.commercesettle.commerce.application.dto;

import java.time.Instant;

public record OrderQuoteView(
    long grossAmount,
    long couponAmount,
    long totalAmount,
    long mileageAmount,
    long externalAmount,
    long productVersion,
    Long eventVersion,
    Instant asOf,
    long sellerCouponAmount,
    long platformCouponAmount,
    long settlementBaseAmount) {}
