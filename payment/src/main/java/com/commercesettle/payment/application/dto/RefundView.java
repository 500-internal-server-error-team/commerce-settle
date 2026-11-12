package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundSagaStep;
import com.commercesettle.payment.domain.model.RefundStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 구매자에게 보여 줄 금융 환불 진행. INTERNAL_PENDING은 외부 환불·마일리지 복원 뒤 원장 상쇄·주문 복구 확인을 기다리는 상태이며 완료가 아니다.
 * sagaStep이 다음에 기다리는 일을 알려 준다.
 */
public record RefundView(
    UUID id,
    UUID paymentId,
    long externalAmount,
    long mileageAmount,
    RefundStatus status,
    RefundSagaStep sagaStep,
    long version,
    Instant createdAt) {

  public static RefundView of(Refund refund, RefundSaga saga) {
    return new RefundView(
        refund.getId(),
        refund.getPaymentId(),
        refund.getExternalAmount(),
        refund.getMileageAmount(),
        refund.getStatus(),
        saga.getStep(),
        Objects.requireNonNullElse(refund.getVersion(), 0L),
        refund.getCreatedAt());
  }
}
