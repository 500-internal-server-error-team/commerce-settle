package com.commercesettle.settlement.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentConfirmedPayloadTest {

  @Test
  @DisplayName("쿠폰·마일리지가 없는 결제와 판매자·플랫폼 쿠폰을 쓴 결제는 계약에 맞는다")
  void validPayments() {
    assertThat(payload(10_000, 0, 0, 10_000, 0, 1_500).amountsMatchContract()).isTrue();
    // G=12,000 S=2,000 P=1,000 → B=10,000, T=9,000, 수수료 1,500
    assertThat(payload(12_000, 2_000, 1_000, 9_000, 0, 1_500).amountsMatchContract()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "G-S≠B, 10000, 0, 0, 10000, 0, 1500, 9999",
    "수수료가 15%가 아님, 10000, 0, 0, 10000, 0, 1000, 10000",
    "쿠폰과 마일리지를 함께 씀, 12000, 2000, 1000, 9000, 1000, 1500, 10000"
  })
  @DisplayName("금액 공식이 하나라도 어긋나면 계약 위반이다")
  void invalidPayments(String name, long g, long s, long p, long t, long m, long fee, long b) {
    PaymentConfirmedPayload valid = payload(g, s, p, t, m, fee);
    PaymentConfirmedPayload changed =
        new PaymentConfirmedPayload(
            valid.grossAmount(),
            valid.couponAmount(),
            valid.totalAmount(),
            valid.mileageAmount(),
            valid.externalAmount(),
            valid.paymentId(),
            valid.orderId(),
            valid.buyerId(),
            valid.sellerId(),
            valid.orderVersion(),
            valid.paymentCycleId(),
            valid.productId(),
            valid.feeAmount(),
            b - fee,
            valid.confirmedAt(),
            valid.policyVersion(),
            valid.sellerCouponAmount(),
            valid.platformCouponAmount(),
            b);

    assertThat(changed.amountsMatchContract()).isFalse();
  }

  /** E는 T−M, 쿠폰 합계는 S+P, B는 T+P, 판매자 몫은 B−수수료로 맞춰 만든다. */
  static PaymentConfirmedPayload payload(long g, long s, long p, long t, long m, long fee) {
    long b = t + p;
    return new PaymentConfirmedPayload(
        g,
        s + p,
        t,
        m,
        t - m,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        fee,
        b - fee,
        OffsetDateTime.parse("2026-10-01T12:00:00+09:00"),
        "v1",
        s,
        p,
        b);
  }
}
