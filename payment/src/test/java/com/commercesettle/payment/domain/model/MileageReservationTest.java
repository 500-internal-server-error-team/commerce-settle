package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MileageReservationTest {

  private static final Instant CREATED_AT = Instant.parse("2026-10-30T00:00:00Z");
  private static final Instant CONSUMED_AT = Instant.parse("2026-10-30T00:01:00Z");

  @Test
  @DisplayName("결제의 사용 마일리지만큼 지갑·결제·회차에 묶인 예약을 만든다")
  void reservesMileageOfPayment() {
    MileageWallet wallet = wallet();
    Payment payment = payment(new PaymentAmounts(10_000, 0, 0, 2_000));

    MileageReservation reservation = MileageReservation.forPayment(wallet, payment, CREATED_AT);

    assertThat(reservation.getStatus()).isEqualTo(MileageReservationStatus.RESERVED);
    assertThat(reservation.getAmount()).isEqualTo(2_000);
    assertThat(reservation.getWalletId()).isEqualTo(wallet.getId());
    assertThat(reservation.getPaymentId()).isEqualTo(payment.getId());
    assertThat(reservation.getCycleId()).isEqualTo(payment.getCurrentCycleId());
    assertThat(reservation.getCreatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  @DisplayName("마일리지를 쓰지 않는 결제는 예약을 만들지 않는다")
  void rejectsPaymentWithoutMileage() {
    Payment payment = payment(new PaymentAmounts(10_000, 0, 0, 0));

    assertThatThrownBy(() -> MileageReservation.forPayment(wallet(), payment, CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("예약을 차감하면 CONSUMED가 되고 수정 시각이 바뀐다")
  void consumesReservation() {
    MileageReservation reservation = reservation();

    reservation.consume(CONSUMED_AT);

    assertThat(reservation.getStatus()).isEqualTo(MileageReservationStatus.CONSUMED);
    assertThat(reservation.getUpdatedAt()).isEqualTo(CONSUMED_AT);
  }

  @Test
  @DisplayName("이미 차감한 예약은 다시 차감할 수 없다")
  void rejectsConsumingTwice() {
    MileageReservation reservation = reservation();
    reservation.consume(CONSUMED_AT);

    assertThatThrownBy(() -> reservation.consume(CONSUMED_AT))
        .isInstanceOf(IllegalStateException.class);
    assertThat(reservation.getStatus()).isEqualTo(MileageReservationStatus.CONSUMED);
  }

  private static MileageReservation reservation() {
    return MileageReservation.forPayment(
        wallet(), payment(new PaymentAmounts(10_000, 0, 0, 2_000)), CREATED_AT);
  }

  private static MileageWallet wallet() {
    return MileageWallet.open(UUID.randomUUID(), 5_000, CREATED_AT);
  }

  private static Payment payment(PaymentAmounts amounts) {
    return Payment.create(
        UUID.randomUUID(),
        0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        amounts,
        "v1",
        CREATED_AT);
  }
}
