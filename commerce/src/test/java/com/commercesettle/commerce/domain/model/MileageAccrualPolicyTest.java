package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MileageAccrualPolicyTest {
  private final MileageAccrualPolicy policy =
      new MileageAccrualPolicy("mileage-accrual-v1", 100, 1_000);

  @ParameterizedTest
  @CsvSource({
    "0, 0", // 마일리지 전액 결제는 외부 결제액이 없어 적립하지 않는다.
    "99, 0", // 1% 버림이 0점이면 적립하지 않는다.
    "100, 1",
    "10199, 101", // 버림
    "99999, 999",
    "100000, 1000", // 한도와 같은 경계
    "10000000, 1000" // 한도로 자른다.
  })
  @DisplayName("외부 결제액의 1%를 버림하고 주문당 한도로 자른다")
  void calculatesFlooredAndCappedAmount(long external, long expected) {
    assertThat(policy.amountFor(external, false)).isEqualTo(expected);
  }

  @Test
  @DisplayName("쿠폰 주문은 외부 결제액과 관계없이 0점이다")
  void excludesCouponOrders() {
    assertThat(policy.amountFor(100_000, true)).isZero();
  }

  @Test
  @DisplayName("적립률은 1~10,000bp, 한도는 1 이상, 버전은 1~40자다")
  void validatesPolicy() {
    assertThatThrownBy(() -> new MileageAccrualPolicy("v", 0, 1_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MileageAccrualPolicy("v", 10_001, 1_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MileageAccrualPolicy("v", 100, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MileageAccrualPolicy(" ", 100, 1_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.amountFor(-1, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
