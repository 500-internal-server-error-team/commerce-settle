package com.commercesettle.payment.domain.model;

import com.commercesettle.payment.domain.exception.InvalidPaymentAmountException;
import jakarta.persistence.Embeddable;

/** 커머스가 확정한 G·S·P·M으로 결제 금액을 계산한다. 생성 시 금액 불변식을 검사한다. */
@Embeddable
public record PaymentAmounts(
    long grossAmount, long sellerCouponAmount, long platformCouponAmount, long mileageAmount) {

  private static final long MAX_GROSS_AMOUNT = 10_000_000;
  private static final long FEE_RATE_PERCENT = 15;

  public PaymentAmounts {
    require(sellerCouponAmount >= 0, "판매자 쿠폰액은 0 이상이어야 합니다.");
    require(platformCouponAmount >= 0, "플랫폼 쿠폰액은 0 이상이어야 합니다.");
    require(mileageAmount >= 0, "마일리지 사용액은 0 이상이어야 합니다.");
    require(
        grossAmount >= 1 && grossAmount <= MAX_GROSS_AMOUNT, "상품대금은 1원 이상 10,000,000원 이하여야 합니다.");
    require(sellerCouponAmount == 0 || platformCouponAmount == 0, "판매자 쿠폰과 플랫폼 쿠폰을 함께 적용할 수 없습니다.");
    boolean couponUsed = sellerCouponAmount > 0 || platformCouponAmount > 0;
    require(!couponUsed || mileageAmount == 0, "쿠폰을 사용하면 마일리지를 함께 쓸 수 없습니다.");

    long total = grossAmount - sellerCouponAmount - platformCouponAmount;
    require(total >= 1, "결제 총액은 1원 이상이어야 합니다.");
    require(mileageAmount <= total, "마일리지 사용액은 결제 총액을 넘을 수 없습니다.");
  }

  /** B = G − S. 수수료·정산 기준액. */
  public long settlementBaseAmount() {
    return grossAmount - sellerCouponAmount;
  }

  /** T = B − P. 구매자 결제 총액. */
  public long totalAmount() {
    return settlementBaseAmount() - platformCouponAmount;
  }

  /** E = T − M. 외부 결제수단 청구액. */
  public long externalAmount() {
    return totalAmount() - mileageAmount;
  }

  /** floor(B × 15 / 100). */
  public long feeAmount() {
    return settlementBaseAmount() * FEE_RATE_PERCENT / 100;
  }

  /** B − 수수료. */
  public long sellerNetAmount() {
    return settlementBaseAmount() - feeAmount();
  }

  /** S + P. 표시용 총할인액. */
  public long couponAmount() {
    return sellerCouponAmount + platformCouponAmount;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new InvalidPaymentAmountException(message);
    }
  }
}
