package com.commercesettle.settlement.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PurchaseConfirmedPayloadTest {
  private static final OffsetDateTime DELIVERED_AT =
      OffsetDateTime.parse("2026-10-26T10:00:00+09:00");
  private static final OffsetDateTime DEADLINE = DELIVERED_AT.plusHours(168);

  @ParameterizedTest(name = "마감 {0}µs → {1}")
  @CsvSource({"-1, false", "0, false", "1, true"})
  @DisplayName("환불은 마감 정각까지 가능하므로 구매확정은 마감 정각을 지난 뒤부터 계약에 맞는다")
  void confirmedAfterDeadline(long offsetMicros, boolean expected) {
    OffsetDateTime confirmedAt = DEADLINE.plusNanos(offsetMicros * 1_000);

    assertThat(payload(confirmedAt, DEADLINE).timesMatchContract()).isEqualTo(expected);
  }

  @Test
  @DisplayName("환불 마감이 배송완료+168시간이 아니면 계약 위반이다")
  void deadlineMustBeDeliveredPlus168Hours() {
    assertThat(payload(DEADLINE.plusHours(1), DEADLINE.minusMinutes(1)).timesMatchContract())
        .isFalse();
  }

  private static PurchaseConfirmedPayload payload(
      OffsetDateTime confirmedAt, OffsetDateTime refundDeadlineAt) {
    return new PurchaseConfirmedPayload(
        UUID.randomUUID(), UUID.randomUUID(), 4, confirmedAt, DELIVERED_AT, refundDeadlineAt);
  }
}
