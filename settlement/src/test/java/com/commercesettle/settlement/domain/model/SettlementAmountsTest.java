package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.domain.exception.InvalidSettlementAmountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettlementAmountsTest {

  @Test
  @DisplayName("플랫폼 쿠폰 결제 예시 금액을 받는다")
  void acceptsPlatformCouponExample() {
    // G=23,000, P=3,000 → B=23,000, T=E=20,000, 수수료 3,450, 판매자 몫 19,550
    assertThatCode(() -> new SettlementAmounts(20_000, 3_000, 23_000, 20_000, 0, 3_450, 19_550))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("마일리지 결제 예시 금액을 받는다")
  void acceptsMileageExample() {
    // G=10,000, M=2,000 → T=B=10,000, E=8,000, 수수료 1,500, 판매자 몫 8,500
    assertThatCode(() -> new SettlementAmounts(10_000, 0, 10_000, 8_000, 2_000, 1_500, 8_500))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("수수료율은 검사하지 않고 받은 수수료를 그대로 둔다")
  void doesNotRecalculateFeeRate() {
    // B=10,000에 15%가 아닌 수수료 1,000(과거 요율 가정)
    assertThatCode(() -> new SettlementAmounts(10_000, 0, 10_000, 10_000, 0, 1_000, 9_000))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "결제 총액 음수, -1, 0, -1, 0, -1, 0, -1",
    "플랫폼 쿠폰액 음수, 10000, -1, 9999, 10000, 0, 1499, 8500",
    "외부 결제액 음수, 10000, 0, 10000, -1, 10001, 1500, 8500",
    "마일리지 음수, 10000, 0, 10000, 10001, -1, 1500, 8500",
    "수수료 음수, 10000, 0, 10000, 10000, 0, -1, 10001",
    "판매자 몫 음수, 10000, 0, 10000, 10000, 0, 10001, -1",
    "T와 E+M 불일치, 10000, 0, 10000, 8000, 1000, 1500, 8500",
    "B와 T+P 불일치, 20000, 3000, 22000, 20000, 0, 3300, 18700",
    "B와 수수료+판매자 몫 불일치, 10000, 0, 10000, 10000, 0, 1500, 8000"
  })
  @DisplayName("금액 불변식을 어기면 거절한다")
  void rejectsBrokenInvariants(
      String caseName,
      long total,
      long platformCoupon,
      long settlementBase,
      long external,
      long mileage,
      long fee,
      long net) {
    assertThatThrownBy(
            () ->
                new SettlementAmounts(
                    total, platformCoupon, settlementBase, external, mileage, fee, net))
        .isInstanceOf(InvalidSettlementAmountException.class);
  }
}
