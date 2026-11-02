package com.commercesettle.payment.application.event;

import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** 결제 확정 이벤트 본문. 금액은 결제를 만들 때 고정한 원금액이다. G-S=B=T+P=E+M+P, 수수료는 B의 15%. */
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
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 확정된 결제에서 만든다. */
  public static PaymentConfirmedPayload of(Payment payment) {
    if (payment.getConfirmedAt() == null) {
      throw new IllegalArgumentException("확정되지 않은 결제입니다.");
    }
    PaymentAmounts amounts = payment.getAmounts();
    return new PaymentConfirmedPayload(
        amounts.grossAmount(),
        amounts.couponAmount(),
        amounts.totalAmount(),
        amounts.mileageAmount(),
        amounts.externalAmount(),
        payment.getId(),
        payment.getOrderId(),
        payment.getBuyerId(),
        payment.getSellerId(),
        payment.getOrderVersion(),
        payment.getCurrentCycleId(),
        payment.getProductId(),
        amounts.feeAmount(),
        amounts.sellerNetAmount(),
        payment.getConfirmedAt().atZone(SEOUL).toOffsetDateTime(),
        payment.getPolicyVersion(),
        amounts.sellerCouponAmount(),
        amounts.platformCouponAmount(),
        amounts.settlementBaseAmount());
  }
}
