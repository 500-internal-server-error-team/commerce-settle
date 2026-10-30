package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Embeddable;

/** 주문 당시의 할인 부담별 금액과 결제 구성을 보존한다. */
@Embeddable
public record OrderAmounts(
    long grossAmount, long sellerCouponAmount, long platformCouponAmount, long mileageAmount) {
  public OrderAmounts {
    if (grossAmount < 1
        || grossAmount > 10_000_000
        || sellerCouponAmount < 0
        || sellerCouponAmount >= grossAmount
        || platformCouponAmount < 0
        || platformCouponAmount >= grossAmount
        || (sellerCouponAmount > 0 && platformCouponAmount > 0)) {
      throw new IllegalArgumentException("Invalid order amounts");
    }
    long total = grossAmount - sellerCouponAmount - platformCouponAmount;
    if (mileageAmount < 0
        || mileageAmount > total
        || ((sellerCouponAmount > 0 || platformCouponAmount > 0) && mileageAmount > 0)) {
      throw new IllegalArgumentException("Invalid order mileage amount");
    }
  }

  public long couponAmount() {
    return sellerCouponAmount + platformCouponAmount;
  }

  public long settlementBaseAmount() {
    return grossAmount - sellerCouponAmount;
  }

  public long totalAmount() {
    return settlementBaseAmount() - platformCouponAmount;
  }

  public long externalAmount() {
    return totalAmount() - mileageAmount;
  }
}
