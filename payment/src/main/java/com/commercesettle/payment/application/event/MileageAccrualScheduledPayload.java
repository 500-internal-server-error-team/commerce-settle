package com.commercesettle.payment.application.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Commerce가 결제 완료를 주문에 반영하면서 보낸 구매 적립 예정. 주문 생성 때 고정한 정책 사본과 예정액이다. OpenAPI
 * MileageAccrualScheduledPayload와 같은 9필드다.
 */
public record MileageAccrualScheduledPayload(
    UUID paymentId,
    UUID orderId,
    UUID buyerId,
    long baseAmount,
    int rateBasisPoints,
    long perOrderCap,
    long accrualAmount,
    String accrualPolicyVersion,
    OffsetDateTime paymentConfirmedAt) {
  public MileageAccrualScheduledPayload {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(buyerId, "buyerId");
    Objects.requireNonNull(accrualPolicyVersion, "accrualPolicyVersion");
    Objects.requireNonNull(paymentConfirmedAt, "paymentConfirmedAt");
  }
}
