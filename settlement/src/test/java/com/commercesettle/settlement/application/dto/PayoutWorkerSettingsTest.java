package com.commercesettle.settlement.application.dto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PayoutWorkerSettingsTest {
  @ParameterizedTest(name = "전체 {0}, 복구 전용 {1}")
  @CsvSource({"0, 0", "8, 0", "8, -1", "8, 8", "2, 3"})
  @DisplayName("복구 전용 슬롯이 0개이거나 전체 슬롯보다 적지 않으면 Worker를 열지 않는다")
  void rejectsInvalidSlots(int maxConcurrency, int recoverySlots) {
    assertThatThrownBy(() -> settings(maxConcurrency, recoverySlots))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "전체 {0}, 복구 전용 {1}")
  @CsvSource({"8, 2", "2, 1"})
  @DisplayName("0 < 복구 전용 슬롯 < 전체 슬롯이면 연다")
  void acceptsValidSlots(int maxConcurrency, int recoverySlots) {
    assertThatCode(() -> settings(maxConcurrency, recoverySlots)).doesNotThrowAnyException();
  }

  private static PayoutWorkerSettings settings(int maxConcurrency, int recoverySlots) {
    return new PayoutWorkerSettings(
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        Duration.ofMinutes(5),
        10,
        maxConcurrency,
        recoverySlots);
  }
}
