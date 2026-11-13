package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.exception.InvalidRefundTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundTest {

  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final Instant LATER = Instant.parse("2026-11-02T00:01:00.1234567Z");

  @Test
  @DisplayName("환불 원거래 재조회가 최초 미확정 시각을 갱신하지 않는다")
  void preservesTheFirstUnknownTimestamp() {
    var payment = confirmed(new PaymentAmounts(10_000, 3_000, 0, 0));
    var refund = Refund.accept(payment, UUID.randomUUID(), NOW);
    refund.reserved(NOW);
    refund.externalUnknown(LATER);
    refund.externalRequested(LATER.plusSeconds(100));
    refund.externalUnknown(LATER.plusSeconds(200));
    assertThat(refund.getUnknownSince())
        .isEqualTo(LATER.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(refund.getUpdatedAt())
        .isEqualTo(LATER.plusSeconds(200).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  @Test
  @DisplayName("확정된 결제의 외부 결제액과 사용 마일리지를 복사해 정산금 예약 대기로 접수한다")
  void acceptsConfirmedPaymentCopyingAmounts() {
    Payment payment = confirmed(new PaymentAmounts(10_000, 0, 0, 2_000));
    UUID requestId = UUID.randomUUID();

    Refund refund = Refund.accept(payment, requestId, NOW);

    assertThat(refund.getPaymentId()).isEqualTo(payment.getId());
    assertThat(refund.getOrderRefundRequestId()).isEqualTo(requestId);
    assertThat(refund.getExternalAmount()).isEqualTo(8_000);
    assertThat(refund.getMileageAmount()).isEqualTo(2_000);
    assertThat(refund.getStatus()).isEqualTo(RefundStatus.RESERVING);
    assertThat(refund.getExternalStatus()).isEqualTo(ExternalRefundStatus.PENDING);
    assertThat(refund.getBusinessKey()).isNotNull();
  }

  @Test
  @DisplayName("마일리지 전액 결제의 환불은 외부 환불이 필요 없다")
  void marksExternalNotRequiredForMileageOnlyPayment() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 10_000)), id(), NOW);

    assertThat(refund.getExternalStatus()).isEqualTo(ExternalRefundStatus.NOT_REQUIRED);
  }

  @Test
  @DisplayName("확정되지 않은 결제는 환불을 접수하지 않는다")
  void rejectsUnconfirmedPayment() {
    Payment payment = payment(new PaymentAmounts(10_000, 0, 0, 0));

    assertThatThrownBy(() -> Refund.accept(payment, id(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("예약 확인 뒤 외부 환불 성공과 마일리지 복원을 거쳐 내부 반영 대기가 된다")
  void followsSuccessPath() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 2_000)), id(), NOW);
    UUID externalRef = UUID.randomUUID();

    refund.reserved(NOW);
    refund.externalRequested(NOW);
    refund.externalSucceeded(externalRef, NOW);
    refund.mileageRestored(LATER);

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.INTERNAL_PENDING);
    assertThat(refund.getExternalStatus()).isEqualTo(ExternalRefundStatus.SUCCEEDED);
    assertThat(refund.getExternalRef()).isEqualTo(externalRef);
    assertThat(refund.getMileageRestoredAt())
        .isEqualTo(Instant.parse("2026-11-02T00:01:00.123456Z"));

    refund.complete(LATER);
    assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
  }

  @Test
  @DisplayName("외부 결제액이 0원이면 외부 호출 없이 복원 대기로 넘어간다")
  void skipsExternalWhenNotRequired() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 10_000)), id(), NOW);
    refund.reserved(NOW);

    assertThatThrownBy(() -> refund.externalRequested(NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
    refund.externalSkipped(NOW);

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.RESTORING);
  }

  @Test
  @DisplayName("외부 결제액이 있으면 외부 환불을 생략할 수 없다")
  void rejectsSkippingRequiredExternalRefund() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 0)), id(), NOW);
    refund.reserved(NOW);

    assertThatThrownBy(() -> refund.externalSkipped(NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
  }

  @Test
  @DisplayName("결과 불명은 같은 원거래 조회로 성공이나 확정 거절로 이어진다")
  void resolvesUnknownByLookup() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 0)), id(), NOW);
    refund.reserved(NOW);
    refund.externalRequested(NOW);

    refund.externalUnknown(NOW);
    refund.externalUnknown(LATER);
    assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
    assertThat(refund.getExternalStatus()).isEqualTo(ExternalRefundStatus.UNKNOWN);

    refund.externalDeclined(LATER);
    assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPENSATING);
    refund.compensated(LATER);
    assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
  }

  @Test
  @DisplayName("확정된 외부 결과는 다시 바꾸지 않는다")
  void doesNotOverwriteSettledExternalResult() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 0)), id(), NOW);
    refund.reserved(NOW);
    refund.externalSucceeded(UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> refund.externalDeclined(NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
    assertThatThrownBy(() -> refund.externalUnknown(NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
  }

  @Test
  @DisplayName("정산금 예약이 거절되면 외부 환불 없이 실패로 끝난다")
  void declinesWhenReservationRejected() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 0)), id(), NOW);

    refund.reservationRejected(NOW);

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
    assertThatThrownBy(() -> refund.reserved(NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
  }

  @Test
  @DisplayName("원장 상쇄·주문 복구 전에는 완료로 바꾸지 않는다")
  void rejectsCompletionBeforeInternalPending() {
    Refund refund = Refund.accept(confirmed(new PaymentAmounts(10_000, 0, 0, 0)), id(), NOW);
    refund.reserved(NOW);
    refund.externalSucceeded(UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> refund.complete(NOW))
        .isInstanceOf(InvalidRefundTransitionException.class);
  }

  /** 승인까지 끝난 결제. 외부 결제액이 있으면 처리 중을 거쳐 확정한다. */
  static Payment confirmed(PaymentAmounts amounts) {
    Payment payment = payment(amounts);
    if (amounts.externalAmount() > 0) {
      payment.startProcessing(NOW);
    }
    payment.confirm(NOW);
    return payment;
  }

  private static Payment payment(PaymentAmounts amounts) {
    return Payment.create(id(), 0, id(), id(), id(), id(), amounts, "v1", NOW);
  }

  private static UUID id() {
    return UUID.randomUUID();
  }
}
