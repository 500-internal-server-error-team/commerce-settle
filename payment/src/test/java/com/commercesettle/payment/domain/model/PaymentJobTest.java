package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentJobTest {

  private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
  private static final UUID PRODUCT_ID = UUID.randomUUID();

  @Test
  @DisplayName("승인 작업은 결제 회차를 업무 키로 PENDING 상태에서 바로 실행 대상이 된다")
  void createsPendingApprovalKeyedByPaymentCycle() {
    Payment payment = payment(new PaymentAmounts(10_000, 0, 0, 0));

    PaymentJob job = PaymentJob.approvalFor(payment, NOW);

    assertThat(job.getPaymentId()).isEqualTo(payment.getId());
    assertThat(job.getOperation()).isEqualTo(PaymentJobOperation.APPROVE);
    assertThat(job.getBusinessKey()).isEqualTo(payment.getCurrentCycleId());
    assertThat(job.getStatus()).isEqualTo(PaymentJobStatus.PENDING);
    assertThat(job.getAttempts()).isZero();
    assertThat(job.getNextRunAt()).isEqualTo(NOW);
    assertThat(job.getCreatedAt()).isEqualTo(NOW);
    assertThat(job.getLeaseOwner()).isNull();
  }

  @Test
  @DisplayName("같은 상품의 서로 다른 결제는 서로 다른 업무 키를 가진다")
  void usesDifferentBusinessKeysForPaymentsOfSameProduct() {
    PaymentJob first = PaymentJob.approvalFor(payment(new PaymentAmounts(10_000, 0, 0, 0)), NOW);
    PaymentJob second = PaymentJob.approvalFor(payment(new PaymentAmounts(10_000, 0, 0, 0)), NOW);

    assertThat(first.getBusinessKey()).isNotEqualTo(second.getBusinessKey());
  }

  @Test
  @DisplayName("외부 결제액이 0원인 마일리지 전액 결제는 승인 작업을 만들지 않는다")
  void rejectsApprovalWhenExternalAmountIsZero() {
    Payment payment = payment(new PaymentAmounts(10_000, 0, 0, 10_000));

    assertThatThrownBy(() -> PaymentJob.approvalFor(payment, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Payment payment(PaymentAmounts amounts) {
    return Payment.create(
        UUID.randomUUID(),
        0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        PRODUCT_ID,
        amounts,
        "v1",
        NOW);
  }
}
