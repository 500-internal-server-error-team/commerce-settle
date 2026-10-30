package com.commercesettle.financemock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.financemock.domain.exception.InsufficientMockFundsException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockAccountTest {
  private static final Instant NOW = Instant.parse("2026-10-30T01:00:00Z");

  @Test
  @DisplayName("가용액을 넘는 선점은 거절하고 예약액을 바꾸지 않는다")
  void rejectsReservationOverAvailableBalance() {
    MockAccount account = account(10_000);
    account.reserve(7_000, NOW);

    assertThatThrownBy(() -> account.reserve(3_001, NOW))
        .isInstanceOf(InsufficientMockFundsException.class);
    assertThat(account.getReservedAmount()).isEqualTo(7_000);
  }

  @Test
  @DisplayName("소비하면 예약액과 잔액이 함께 줄고, 해제하면 예약액만 준다")
  void consumeDebitsBalanceAndReleaseOnlyFreesReservation() {
    MockAccount account = account(10_000);
    account.reserve(3_000, NOW);
    account.reserve(2_000, NOW);

    account.consume(3_000, NOW);
    account.release(2_000, NOW);

    assertThat(account.getBalance()).isEqualTo(7_000);
    assertThat(account.getReservedAmount()).isZero();
    assertThat(account.getOpeningBalance()).isEqualTo(10_000);
  }

  @Test
  @DisplayName("선점하지 않은 금액은 소비할 수 없다")
  void rejectsConsumingUnreservedAmount() {
    MockAccount account = account(10_000);
    account.reserve(1_000, NOW);

    assertThatThrownBy(() -> account.consume(1_001, NOW)).isInstanceOf(IllegalStateException.class);
    assertThat(account.getBalance()).isEqualTo(10_000);
  }

  private static MockAccount account(long openingBalance) {
    return MockAccount.open("PAYOUT", "funding", AccountPurpose.PAYOUT, openingBalance, NOW);
  }
}
