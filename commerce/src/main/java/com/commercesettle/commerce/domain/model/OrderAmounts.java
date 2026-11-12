package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Embeddable;

/** 주문 당시의 할인 부담별 금액과 결제 구성을 보존한다. 판매자 수수료 계산도 여기서 한 번만 정의한다. */
@Embeddable
public record OrderAmounts(
    long grossAmount, long sellerCouponAmount, long platformCouponAmount, long mileageAmount) {
  /** 판매자에게 부과하는 PG·부가세 포함 총수수료율(%). 기준액은 판매자 쿠폰을 뺀 B다. */
  public static final long FEE_PERCENT = 15;

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

  /** 정산 기준액 B의 수수료 F=floor(B×15/100). 판매자 쿠폰 캠페인의 정산액 검증도 같은 계산을 쓴다. */
  public static long feeOf(long settlementBaseAmount) {
    return settlementBaseAmount * FEE_PERCENT / 100;
  }

  public long feeAmount() {
    return feeOf(settlementBaseAmount());
  }

  /** 판매자 순정산액 N=B−F. */
  public long sellerNetAmount() {
    return settlementBaseAmount() - feeAmount();
  }
}
