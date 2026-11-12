package com.commercesettle.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.payment.domain.exception.InvalidPaymentAmountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentAmountsTest {

  @Test
  @DisplayName("플랫폼 쿠폰은 외부 결제액만 줄이고 수수료·판매자 몫은 상품대금 기준이다")
  void keepsSettlementBaseWhenPlatformCouponIsUsed() {
    PaymentAmounts amounts = new PaymentAmounts(23_000, 0, 3_000, 0);

    assertThat(amounts.settlementBaseAmount()).isEqualTo(23_000);
    assertThat(amounts.totalAmount()).isEqualTo(20_000);
    assertThat(amounts.externalAmount()).isEqualTo(20_000);
    assertThat(amounts.feeAmount()).isEqualTo(3_450);
    assertThat(amounts.sellerNetAmount()).isEqualTo(19_550);
  }

  @Test
  @DisplayName("마일리지는 외부 결제액만 줄이고 수수료·판매자 몫은 그대로다")
  void splitsTotalIntoMileageAndExternalAmount() {
    PaymentAmounts amounts = new PaymentAmounts(10_000, 0, 0, 2_000);

    assertThat(amounts.totalAmount()).isEqualTo(10_000);
    assertThat(amounts.externalAmount()).isEqualTo(8_000);
    assertThat(amounts.feeAmount()).isEqualTo(1_500);
    assertThat(amounts.sellerNetAmount()).isEqualTo(8_500);
  }

  @Test
  @DisplayName("수수료는 원 미만을 버린다")
  void truncatesFeeBelowOneWon() {
    PaymentAmounts amounts = new PaymentAmounts(199, 0, 0, 0);

    assertThat(amounts.feeAmount()).isEqualTo(29);
    assertThat(amounts.sellerNetAmount()).isEqualTo(170);
  }

  @ParameterizedTest
  @CsvSource({
    "23000, 0, 3000, 0",
    "10000, 0, 0, 2000",
    "20000, 2000, 0, 0",
    "10000, 0, 0, 10000",
    "199, 0, 0, 0"
  })
  @DisplayName("외부 결제액·마일리지·플랫폼 쿠폰의 합은 판매자 몫과 수수료의 합과 같다")
  void preservesSettlementBase(long gross, long sellerCoupon, long platformCoupon, long mileage) {
    PaymentAmounts amounts = new PaymentAmounts(gross, sellerCoupon, platformCoupon, mileage);

    long paid = amounts.externalAmount() + mileage + platformCoupon;
    long allocated = amounts.sellerNetAmount() + amounts.feeAmount();
    assertThat(paid).isEqualTo(amounts.settlementBaseAmount()).isEqualTo(allocated);
  }

  @ParameterizedTest
  @CsvSource({"10000, 0, 0, 10000, 0", "10000000, 0, 0, 0, 10000000", "1000, 0, 999, 0, 1"})
  @DisplayName("마일리지 전액 결제·상품대금 상한·결제 총액 1원은 허용한다")
  void acceptsBoundaryAmounts(
      long gross, long sellerCoupon, long platformCoupon, long mileage, long external) {
    PaymentAmounts amounts = new PaymentAmounts(gross, sellerCoupon, platformCoupon, mileage);

    assertThat(amounts.externalAmount()).isEqualTo(external);
  }

  @ParameterizedTest
  @CsvSource({
    "10000, -1, 0, 0",
    "10000, 0, -1, 0",
    "10000, 0, 0, -1",
    "0, 0, 0, 0",
    "10000001, 0, 0, 0",
    "10000, 1000, 1000, 0",
    "10000, 0, 1000, 500",
    "10000, 1000, 0, 500",
    "3000, 0, 3000, 0",
    "10000, 0, 0, 10001"
  })
  @DisplayName("금액 불변식을 위반하면 생성하지 않는다")
  void rejectsInvalidAmounts(long gross, long sellerCoupon, long platformCoupon, long mileage) {
    assertThatThrownBy(() -> new PaymentAmounts(gross, sellerCoupon, platformCoupon, mileage))
        .isInstanceOf(InvalidPaymentAmountException.class);
  }
}
