package com.commercesettle.payment.application.dto;

import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 운영자에게 보여 줄 결제 작업. resourceId는 환불 작업이면 환불 ID, 그 밖에는 결제 ID다. 임대 token은 내보내지 않는다. */
public record PaymentJobView(
    UUID id,
    UUID resourceId,
    String operation,
    PaymentJobStatus status,
    int attempts,
    Instant nextRunAt,
    String lastErrorCode,
    long version,
    Instant createdAt) {
  public static PaymentJobView of(PaymentJob job) {
    return new PaymentJobView(
        job.getId(),
        job.getRefundId() == null ? job.getPaymentId() : job.getRefundId(),
        job.getOperation().name(),
        job.getStatus(),
        job.getAttempts(),
        job.getNextRunAt(),
        job.getLastErrorCode(),
        Objects.requireNonNullElse(job.getVersion(), 0L),
        job.getCreatedAt());
  }
}
