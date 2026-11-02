package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.domain.model.PaymentAttempt;
import com.commercesettle.payment.domain.model.PaymentAttemptStatus;
import java.time.Instant;
import java.util.UUID;

/** 구매자에게 보여 줄 외부 승인 시도. 계정·재원 참조와 외부 거래 ID는 내보내지 않는다. */
public record PaymentAttemptView(
    UUID id,
    UUID paymentId,
    int attemptNo,
    String providerId,
    String method,
    long amount,
    PaymentAttemptStatus status,
    Instant createdAt) {
  public static PaymentAttemptView of(PaymentAttempt attempt) {
    return new PaymentAttemptView(
        attempt.getId(),
        attempt.getPaymentId(),
        attempt.getAttemptNo(),
        attempt.getProviderId(),
        attempt.getMethod(),
        attempt.getAmount(),
        attempt.getStatus(),
        attempt.getCreatedAt());
  }
}
