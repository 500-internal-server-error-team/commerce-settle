package com.commercesettle.commerce.domain.model;

import java.util.Objects;

/**
 * 구매 적립 정책. 외부 결제액에 적립률(basis point 정수)을 곱해 버리고 주문당 한도로 자른다. 쿠폰 주문과 사용한 마일리지에는 적립하지 않는다. 주문 생성 때
 * 사본을 주문에 고정하므로 정책을 바꿔도 이미 만든 주문의 예정액은 바뀌지 않는다.
 */
public record MileageAccrualPolicy(String version, int rateBasisPoints, long perOrderCap) {
  /** 적립률 분모. */
  public static final long BASIS_POINTS = 10_000;

  public MileageAccrualPolicy {
    Objects.requireNonNull(version, "version");
    if (version.isBlank()
        || version.length() > 40
        || rateBasisPoints < 1
        || rateBasisPoints > BASIS_POINTS
        || perOrderCap < 1) {
      throw new IllegalArgumentException("Invalid mileage accrual policy");
    }
  }

  /**
   * 쿠폰 주문은 0점이다. 그 외는 {@code min(perOrderCap, floor(externalAmount × rateBasisPoints / 10,000))}.
   */
  public long amountFor(long externalAmount, boolean couponOrder) {
    if (externalAmount < 0) throw new IllegalArgumentException("externalAmount");
    if (couponOrder) return 0;
    return Math.min(
        perOrderCap, Math.multiplyExact(externalAmount, rateBasisPoints) / BASIS_POINTS);
  }
}
