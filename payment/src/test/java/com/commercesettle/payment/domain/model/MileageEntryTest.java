package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MileageEntryTest {

  private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

  @Test
  @DisplayName("예약 기록은 결제 회차 ID를 업무 키로 쓰고 바뀌지 않은 잔액을 남긴다")
  void recordsReservationKeyedByCycle() {
    MileageWallet wallet = MileageWallet.open(UUID.randomUUID(), 2_000, NOW);
    MileageReservation reservation = reserve(wallet, 1_500);

    MileageEntry entry = MileageEntry.reserve(wallet, reservation, NOW);

    assertThat(entry.getKind()).isEqualTo(MileageEntryKind.RESERVE);
    assertThat(entry.getBusinessRef()).isEqualTo(reservation.getCycleId());
    assertThat(entry.getWalletId()).isEqualTo(wallet.getId());
    assertThat(entry.getPaymentId()).isEqualTo(reservation.getPaymentId());
    assertThat(entry.getAmount()).isEqualTo(1_500);
    assertThat(entry.getBalanceAfter()).isEqualTo(2_000);
    assertThat(entry.getRefundId()).isNull();
  }

  @Test
  @DisplayName("차감 기록은 결제 ID를 업무 키로 쓰고 차감 뒤 잔액을 남긴다")
  void recordsDebitKeyedByPayment() {
    MileageWallet wallet = MileageWallet.open(UUID.randomUUID(), 2_000, NOW);
    MileageReservation reservation = reserve(wallet, 1_500);
    wallet.consumeReserved(1_500, NOW);

    MileageEntry entry = MileageEntry.debit(wallet, reservation, NOW);

    assertThat(entry.getKind()).isEqualTo(MileageEntryKind.DEBIT);
    assertThat(entry.getBusinessRef()).isEqualTo(reservation.getPaymentId());
    assertThat(entry.getAmount()).isEqualTo(1_500);
    assertThat(entry.getBalanceAfter()).isEqualTo(500);
  }

  @Test
  @DisplayName("기록하는 것만으로 지갑 잔액과 예약액은 바뀌지 않는다")
  void doesNotChangeWallet() {
    MileageWallet wallet = MileageWallet.open(UUID.randomUUID(), 2_000, NOW);
    MileageReservation reservation = reserve(wallet, 1_500);

    MileageEntry.debit(wallet, reservation, NOW);

    assertThat(wallet.getBalance()).isEqualTo(2_000);
    assertThat(wallet.getReservedAmount()).isEqualTo(1_500);
  }

  @Test
  @DisplayName("기록 시각은 마이크로초 단위로 남긴다")
  void truncatesCreatedAtToMicroseconds() {
    MileageWallet wallet = MileageWallet.open(UUID.randomUUID(), 2_000, NOW);

    MileageEntry entry =
        MileageEntry.reserve(
            wallet, reserve(wallet, 1_500), Instant.parse("2026-10-30T00:00:00.123456789Z"));

    assertThat(entry.getCreatedAt()).isEqualTo(Instant.parse("2026-10-30T00:00:00.123456Z"));
  }

  @Test
  @DisplayName("다른 지갑의 예약은 기록하지 않는다")
  void rejectsReservationOfAnotherWallet() {
    MileageWallet wallet = MileageWallet.open(UUID.randomUUID(), 2_000, NOW);
    MileageWallet other = MileageWallet.open(UUID.randomUUID(), 2_000, NOW);
    MileageReservation reservation = reserve(other, 1_500);

    assertThatThrownBy(() -> MileageEntry.reserve(wallet, reservation, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MileageReservation reserve(MileageWallet wallet, long mileage) {
    wallet.reserve(mileage, NOW);
    Payment payment =
        Payment.create(
            UUID.randomUUID(),
            0,
            UUID.randomUUID(),
            wallet.getBuyerId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new PaymentAmounts(10_000, 0, 0, mileage),
            "v1",
            NOW);
    return MileageReservation.forPayment(wallet, payment, NOW);
  }
}
