package com.commercesettle.commerce.domain.model;

/**
 * 월간 우수고객 선정자의 혜택 등급. 등급은 선정된 고객의 할인액만 정하며 순위 밖 고객은 금액을 충족해도 발급하지 않는다. 전월 외부 결제액 합계 30만 원 미만은 브론즈,
 * 50만 원 미만은 실버, 그 이상은 골드다.
 */
public enum CouponGrade {
  BRONZE(3_000, 50_000),
  SILVER(5_000, 70_000),
  GOLD(7_000, 90_000);

  static final long SILVER_SPEND = 300_000;
  static final long GOLD_SPEND = 500_000;

  private final long discountAmount;
  private final long minOrderAmount;

  CouponGrade(long discountAmount, long minOrderAmount) {
    this.discountAmount = discountAmount;
    this.minOrderAmount = minOrderAmount;
  }

  public static CouponGrade of(long externalSpend) {
    if (externalSpend <= 0) {
      throw new IllegalArgumentException("external spend must be positive");
    }
    return externalSpend >= GOLD_SPEND ? GOLD : externalSpend >= SILVER_SPEND ? SILVER : BRONZE;
  }

  public long discountAmount() {
    return discountAmount;
  }

  public long minOrderAmount() {
    return minOrderAmount;
  }
}
