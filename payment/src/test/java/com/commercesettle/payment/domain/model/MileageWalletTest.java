package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.exception.InsufficientMileageException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MileageWalletTest {

  private static final Instant OPENED_AT = Instant.parse("2026-10-30T00:00:00Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-10-30T00:01:00Z");

  @Test
  @DisplayName("지갑을 열면 예약액이 없고 가용 잔액은 잔액과 같다")
  void opensWithoutReservation() {
    MileageWallet wallet = wallet(2_000);

    assertThat(wallet.getBalance()).isEqualTo(2_000);
    assertThat(wallet.getReservedAmount()).isZero();
    assertThat(wallet.available()).isEqualTo(2_000);
    assertThat(wallet.getVersion()).isNull();
  }

  @Test
  @DisplayName("예약은 잔액을 그대로 두고 예약액만 늘려 가용 잔액을 줄인다")
  void reservesWithoutChangingBalance() {
    MileageWallet wallet = wallet(2_000);

    wallet.reserve(1_500, CHANGED_AT);

    assertThat(wallet.getBalance()).isEqualTo(2_000);
    assertThat(wallet.getReservedAmount()).isEqualTo(1_500);
    assertThat(wallet.available()).isEqualTo(500);
    assertThat(wallet.getUpdatedAt()).isEqualTo(CHANGED_AT);
  }

  @Test
  @DisplayName("가용 잔액보다 많이 예약하면 거절하고 예약액은 바뀌지 않는다")
  void rejectsReservationOverAvailable() {
    MileageWallet wallet = wallet(2_000);
    wallet.reserve(1_500, CHANGED_AT);

    assertThatThrownBy(() -> wallet.reserve(1_500, CHANGED_AT))
        .isInstanceOf(InsufficientMileageException.class);
    assertThat(wallet.getReservedAmount()).isEqualTo(1_500);
  }

  @Test
  @DisplayName("가용 잔액 전부는 예약할 수 있다")
  void reservesExactlyAvailable() {
    MileageWallet wallet = wallet(2_000);

    wallet.reserve(2_000, CHANGED_AT);

    assertThat(wallet.available()).isZero();
  }

  @Test
  @DisplayName("예약한 금액을 차감하면 잔액과 예약액이 함께 줄어든다")
  void consumesReservedAmount() {
    MileageWallet wallet = wallet(2_000);
    wallet.reserve(1_500, CHANGED_AT);

    wallet.consumeReserved(1_500, CHANGED_AT);

    assertThat(wallet.getBalance()).isEqualTo(500);
    assertThat(wallet.getReservedAmount()).isZero();
    assertThat(wallet.available()).isEqualTo(500);
  }

  @Test
  @DisplayName("예약액보다 많이 차감하면 거절하고 잔액은 바뀌지 않는다")
  void rejectsConsumingMoreThanReserved() {
    MileageWallet wallet = wallet(2_000);
    wallet.reserve(1_000, CHANGED_AT);

    assertThatThrownBy(() -> wallet.consumeReserved(1_500, CHANGED_AT))
        .isInstanceOf(IllegalStateException.class);
    assertThat(wallet.getBalance()).isEqualTo(2_000);
    assertThat(wallet.getReservedAmount()).isEqualTo(1_000);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("0점 이하는 예약하거나 차감할 수 없다")
  void rejectsNonPositiveAmount(long amount) {
    MileageWallet wallet = wallet(2_000);

    assertThatThrownBy(() -> wallet.reserve(amount, CHANGED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> wallet.consumeReserved(amount, CHANGED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("음수 잔액으로는 지갑을 열 수 없다")
  void rejectsNegativeOpeningBalance() {
    assertThatThrownBy(() -> wallet(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  private static MileageWallet wallet(long balance) {
    return MileageWallet.open(UUID.randomUUID(), balance, OPENED_AT);
  }
}
