package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.exception.InvalidPaymentTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

  private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T00:00:00Z");

  @Test
  @DisplayName("결제는 PENDING으로 생성되고 확정 시각이 없다")
  void startsAsPendingWithoutConfirmedAt() {
    Payment payment = cardPayment();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.getConfirmedAt()).isNull();
  }

  @Test
  @DisplayName("외부 결제액이 있으면 승인 처리 뒤 확정하고 확정 시각을 남긴다")
  void confirmsCardPaymentAfterProcessing() {
    Payment payment = cardPayment();

    payment.startProcessing();
    payment.confirm(CONFIRMED_AT);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    assertThat(payment.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
  }

  @Test
  @DisplayName("마일리지 전액 결제는 승인 처리 없이 바로 확정한다")
  void confirmsMileageOnlyPaymentFromPending() {
    Payment payment = mileageOnlyPayment();

    payment.confirm(CONFIRMED_AT);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    assertThat(payment.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
  }

  @Test
  @DisplayName("외부 결제액이 있으면 승인 처리 없이 확정할 수 없다")
  void rejectsDirectConfirmWhenExternalAmountExists() {
    Payment payment = cardPayment();

    assertThatThrownBy(() -> payment.confirm(CONFIRMED_AT))
        .isInstanceOf(InvalidPaymentTransitionException.class);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.getConfirmedAt()).isNull();
  }

  @Test
  @DisplayName("마일리지 전액 결제는 승인 처리로 넘기지 않는다")
  void rejectsProcessingWhenExternalAmountIsZero() {
    Payment payment = mileageOnlyPayment();

    assertThatThrownBy(payment::startProcessing)
        .isInstanceOf(InvalidPaymentTransitionException.class);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  @DisplayName("결과가 미확정이면 조회 결과로 확정할 수 있다")
  void confirmsUnknownPayment() {
    Payment payment = cardPayment();
    payment.startProcessing();
    payment.markUnknown();

    payment.confirm(CONFIRMED_AT);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
  }

  @Test
  @DisplayName("결과가 미확정인 결제는 취소할 수 없다")
  void rejectsCancelWhenUnknown() {
    Payment payment = cardPayment();
    payment.startProcessing();
    payment.markUnknown();

    assertThatThrownBy(payment::cancel).isInstanceOf(InvalidPaymentTransitionException.class);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
  }

  @Test
  @DisplayName("거절된 결제는 다시 승인 처리할 수 있다")
  void retriesDeclinedPayment() {
    Payment payment = cardPayment();
    payment.startProcessing();
    payment.decline();

    payment.startProcessing();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
  }

  @Test
  @DisplayName("확정된 결제는 취소할 수 없다")
  void rejectsCancelWhenConfirmed() {
    Payment payment = mileageOnlyPayment();
    payment.confirm(CONFIRMED_AT);

    assertThatThrownBy(payment::cancel).isInstanceOf(InvalidPaymentTransitionException.class);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
  }

  private static Payment cardPayment() {
    return create(new PaymentAmounts(10_000, 0, 0, 0));
  }

  private static Payment mileageOnlyPayment() {
    return create(new PaymentAmounts(10_000, 0, 0, 10_000));
  }

  private static Payment create(PaymentAmounts amounts) {
    return Payment.create(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amounts, "v1");
  }
}
