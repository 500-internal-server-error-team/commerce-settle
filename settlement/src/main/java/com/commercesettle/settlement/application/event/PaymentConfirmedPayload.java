package com.commercesettle.settlement.application.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI {@code PaymentConfirmedPayload}. 금액은 Payment가 확정한 값이고 Settlement는 다시 계산해 덮어쓰지 않는다. */
public record PaymentConfirmedPayload(
    long grossAmount,
    long couponAmount,
    long totalAmount,
    long mileageAmount,
    long externalAmount,
    UUID paymentId,
    UUID orderId,
    UUID buyerId,
    UUID sellerId,
    long orderVersion,
    UUID paymentCycleId,
    UUID productId,
    long feeAmount,
    long sellerNetAmount,
    OffsetDateTime confirmedAt,
    String policyVersion,
    long sellerCouponAmount,
    long platformCouponAmount,
    long settlementBaseAmount) {

  private static final long FEE_PERCENT = 15;

  /**
   * 금액 계약: G−S=B, B=T+P, T=E+M, couponAmount=S+P, 수수료=floor(B×15/100), 판매자 몫=B−수수료, 쿠폰을 쓰면 M=0.
   * 하나라도 어긋나면 반영하지 않는다.
   */
  public boolean amountsMatchContract() {
    long b = settlementBaseAmount;
    return grossAmount - sellerCouponAmount == b
        && b == totalAmount + platformCouponAmount
        && totalAmount == externalAmount + mileageAmount
        && couponAmount == sellerCouponAmount + platformCouponAmount
        && feeAmount == b * FEE_PERCENT / 100
        && sellerNetAmount == b - feeAmount
        && (couponAmount == 0 || mileageAmount == 0);
  }
}
