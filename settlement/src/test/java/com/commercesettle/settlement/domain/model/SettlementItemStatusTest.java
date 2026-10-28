package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettlementItemStatusTest {
  @ParameterizedTest
  @CsvSource({
    "WAITING_CONFIRMATION, PAYABLE",
    "WAITING_CONFIRMATION, REFUND_RESERVED",
    "PAYABLE, REFUND_RESERVED",
    "PAYABLE, PAYOUT_RESERVED",
    "REFUND_RESERVED, REFUNDED",
    "REFUND_RESERVED, WAITING_CONFIRMATION",
    "REFUND_RESERVED, PAYABLE",
    "PAYOUT_RESERVED, PAID"
  })
  @DisplayName("전이표에 있는 상태로 이동할 수 있다")
  void allowsDefinedTransitions(SettlementItemStatus from, SettlementItemStatus to) {
    assertThat(from.canTransitionTo(to)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "WAITING_CONFIRMATION, PAYOUT_RESERVED",
    "WAITING_CONFIRMATION, PAID",
    "PAYABLE, WAITING_CONFIRMATION",
    "REFUND_RESERVED, PAYOUT_RESERVED",
    "PAYOUT_RESERVED, PAYABLE",
    "PAYOUT_RESERVED, REFUND_RESERVED",
    "REFUNDED, PAYABLE",
    "PAID, REFUND_RESERVED",
    "PAYABLE, PAYABLE"
  })
  @DisplayName("전이표에 없는 상태로는 이동할 수 없다")
  void rejectsUndefinedTransitions(SettlementItemStatus from, SettlementItemStatus to) {
    assertThat(from.canTransitionTo(to)).isFalse();
  }
}
