package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class PaymentStatusTest {
  @ParameterizedTest
  @CsvSource({
    "PENDING, PROCESSING",
    "PENDING, CONFIRMED",
    "PENDING, CANCELLED",
    "PROCESSING, CONFIRMED",
    "PROCESSING, DECLINED",
    "PROCESSING, UNKNOWN",
    "UNKNOWN, CONFIRMED",
    "UNKNOWN, DECLINED",
    "DECLINED, PROCESSING",
    "DECLINED, CANCELLED"
  })
  @DisplayName("전이표에 있는 상태로 이동할 수 있다")
  void allowsDefinedTransitions(PaymentStatus from, PaymentStatus to) {
    assertThat(from.canTransitionTo(to)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "UNKNOWN, CANCELLED",
    "PROCESSING, CANCELLED",
    "CONFIRMED, PROCESSING",
    "CANCELLED, PENDING"
  })
  @DisplayName("전이표에 없는 상태로는 이동할 수 없다")
  void rejectsUndefinedTransitions(PaymentStatus from, PaymentStatus to) {
    assertThat(from.canTransitionTo(to)).isFalse();
  }
}
