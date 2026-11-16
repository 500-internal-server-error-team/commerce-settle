package com.commercesettle.commerce.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboxConfigurationTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 8, 64})
  @DisplayName("허용 범위의 발행 병렬도는 실행 슬롯과 대기 용량에 그대로 쓴다")
  void usesAllowedLimit(int maxInFlight) {
    var executor = new OutboxConfiguration().outboxExecutor(maxInFlight);

    assertThat(executor.getCorePoolSize()).isEqualTo(maxInFlight);
    assertThat(executor.getMaxPoolSize()).isEqualTo(maxInFlight);
    assertThat(executor.getQueueCapacity()).isEqualTo(maxInFlight);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 65})
  @DisplayName("1~64 밖의 발행 병렬도는 기동을 거절한다")
  void rejectsLimitOutsideRange(int maxInFlight) {
    var configuration = new OutboxConfiguration();

    assertThatThrownBy(() -> configuration.outboxExecutor(maxInFlight))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
