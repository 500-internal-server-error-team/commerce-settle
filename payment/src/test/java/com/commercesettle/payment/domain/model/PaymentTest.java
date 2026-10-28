package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.exception.InvalidPaymentTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

  private static final Instant CREATED_AT = Instant.parse("2026-10-28T00:00:00Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-10-28T00:01:00Z");
  private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T00:02:00Z");

  @Test
  @DisplayName("결제는 PENDING으로 생성되고 생성·수정 시각이 같으며 확정 시각이 없다")
  void startsAsPendingWithCreationTime() {
    Payment payment = cardPayment();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(payment.getUpdatedAt()).isEqualTo(CREATED_AT);
    assertThat(payment.getConfirmedAt()).isNull();
  }

  @Test
  @DisplayName("시각은 마이크로초 단위로 저장한다")
  void truncatesTimesToMicroseconds() {
    Payment payment =
        create(
            new PaymentAmounts(10_000, 0, 0, 0), Instant.parse("2026-10-28T00:00:00.123456789Z"));

    assertThat(payment.getCreatedAt()).isEqualTo(Instant.parse("2026-10-28T00:00:00.123456Z"));
  }

  @Test
  @DisplayName("외부 결제액이 있으면 승인 처리 뒤 확정하고 확정 시각을 남긴다")
  void confirmsCardPaymentAfterProcessing() {
    Payment payment = cardPayment();

    payment.startProcessing(CHANGED_AT);
    payment.confirm(CONFIRMED_AT);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    assertThat(payment.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
    assertThat(payment.getUpdatedAt()).isEqualTo(CONFIRMED_AT);
    assertThat(payment.getCreatedAt()).isEqualTo(CREATED_AT);
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
    assertThat(payment.getUpdatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  @DisplayName("마일리지 전액 결제는 승인 처리로 넘기지 않는다")
  void rejectsProcessingWhenExternalAmountIsZero() {
    Payment payment = mileageOnlyPayment();

    assertThatThrownBy(() -> payment.startProcessing(CHANGED_AT))
        .isInstanceOf(InvalidPaymentTransitionException.class);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  @DisplayName("결과가 미확정이면 조회 결과로 확정할 수 있다")
  void confirmsUnknownPayment() {
    Payment payment = cardPayment();
    payment.startProcessing(CHANGED_AT);
    payment.markUnknown(CHANGED_AT);

    payment.confirm(CONFIRMED_AT);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
  }

  @Test
  @DisplayName("결과가 미확정인 결제는 취소할 수 없고 수정 시각도 바뀌지 않는다")
  void rejectsCancelWhenUnknown() {
    Payment payment = cardPayment();
    payment.startProcessing(CHANGED_AT);
    payment.markUnknown(CHANGED_AT);

    assertThatThrownBy(() -> payment.cancel(CONFIRMED_AT))
        .isInstanceOf(InvalidPaymentTransitionException.class);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    assertThat(payment.getUpdatedAt()).isEqualTo(CHANGED_AT);
  }

  @Test
  @DisplayName("거절된 결제는 다시 승인 처리할 수 있다")
  void retriesDeclinedPayment() {
    Payment payment = cardPayment();
    payment.startProcessing(CHANGED_AT);
    payment.decline(CHANGED_AT);

    payment.startProcessing(CONFIRMED_AT);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(payment.getUpdatedAt()).isEqualTo(CONFIRMED_AT);
  }

  @Test
  @DisplayName("확정된 결제는 취소할 수 없다")
  void rejectsCancelWhenConfirmed() {
    Payment payment = mileageOnlyPayment();
    payment.confirm(CONFIRMED_AT);

    assertThatThrownBy(() -> payment.cancel(CONFIRMED_AT))
        .isInstanceOf(InvalidPaymentTransitionException.class);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
  }

  private static Payment cardPayment() {
    return create(new PaymentAmounts(10_000, 0, 0, 0), CREATED_AT);
  }

  private static Payment mileageOnlyPayment() {
    return create(new PaymentAmounts(10_000, 0, 0, 10_000), CREATED_AT);
  }

  private static Payment create(PaymentAmounts amounts, Instant now) {
    return Payment.create(
        UUID.randomUUID(),
        0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        amounts,
        "v1",
        now);
  }
}
