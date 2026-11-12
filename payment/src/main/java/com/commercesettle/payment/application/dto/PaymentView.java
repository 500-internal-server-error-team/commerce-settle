package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 구매자에게 보여 줄 결제 진행. method는 결제를 처음 만든 결제 요청의 외부 결제수단이며 외부 결제액이 없으면 null이다. providerId는 외부 승인 시도가
 * 생기기 전까지 null이다.
 */
public record PaymentView(
    UUID id,
    UUID orderId,
    PaymentStatus status,
    long totalAmount,
    long mileageAmount,
    long externalAmount,
    String method,
    String providerId,
    Instant confirmedAt,
    long version,
    Instant createdAt,
    long platformCouponAmount,
    long settlementBaseAmount) {

  public static PaymentView of(Payment payment, String method) {
    return new PaymentView(
        payment.getId(),
        payment.getOrderId(),
        payment.getStatus(),
        payment.getAmounts().totalAmount(),
        payment.getAmounts().mileageAmount(),
        payment.getAmounts().externalAmount(),
        method,
        null,
        payment.getConfirmedAt(),
        // 저장 전 결제는 버전이 없으며 처음 저장될 때 0이 된다.
        Objects.requireNonNullElse(payment.getVersion(), 0L),
        payment.getCreatedAt(),
        payment.getAmounts().platformCouponAmount(),
        payment.getAmounts().settlementBaseAmount());
  }
}
