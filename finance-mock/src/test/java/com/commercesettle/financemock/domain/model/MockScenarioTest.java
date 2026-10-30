package com.commercesettle.financemock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockScenarioTest {
  private static final Instant NOW = Instant.parse("2026-10-30T01:00:00Z");
  private static final UUID KEY = UUID.fromString("7f0c6a52-6a4e-4f39-9d55-0d0a8f2b6e11");

  @Test
  @DisplayName("같은 seed와 업무 키는 매번 같은 지연·결과를 만든다")
  void sameSeedAndKeyGiveSameOutcome() {
    MockScenario scenario = scenario(new BigDecimal("0.5"), new BigDecimal("0.5"), 7L);

    long resultSeed = scenario.resultSeed(MockOperation.PAYOUT, KEY);

    assertThat(
            scenario(new BigDecimal("0.5"), new BigDecimal("0.5"), 7L)
                .resultSeed(MockOperation.PAYOUT, KEY))
        .isEqualTo(resultSeed);
    assertThat(scenario.payoutOutcome(resultSeed)).isEqualTo(scenario.payoutOutcome(resultSeed));
    assertThat(scenario.payoutOutcome(resultSeed).delayMs()).isBetween(10, 20);
  }

  @Test
  @DisplayName("seed가 다르면 같은 업무 키도 다른 결과 seed를 쓴다")
  void differentSeedChangesResultSeed() {
    long first =
        scenario(BigDecimal.ZERO, BigDecimal.ZERO, 7L).resultSeed(MockOperation.PAYOUT, KEY);
    long second =
        scenario(BigDecimal.ZERO, BigDecimal.ZERO, 8L).resultSeed(MockOperation.PAYOUT, KEY);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("확률 0은 결과를 바꾸지 않고 확률 1은 항상 거절·유실을 만든다")
  void rateBoundsAreExact() {
    MockScenario never = scenario(BigDecimal.ZERO, BigDecimal.ZERO, 7L);
    MockScenario always = scenario(BigDecimal.ONE, BigDecimal.ONE, 7L);

    for (int i = 0; i < 100; i++) {
      UUID key = UUID.randomUUID();
      assertThat(never.payoutOutcome(never.resultSeed(MockOperation.PAYOUT, key)))
          .extracting(
              MockScenario.PayoutOutcome::rejected, MockScenario.PayoutOutcome::responseLost)
          .containsExactly(false, false);
      assertThat(always.payoutOutcome(always.resultSeed(MockOperation.PAYOUT, key)))
          .extracting(
              MockScenario.PayoutOutcome::rejected, MockScenario.PayoutOutcome::responseLost)
          .containsExactly(true, true);
    }
  }

  private static MockScenario scenario(BigDecimal rejection, BigDecimal loss, long seed) {
    return MockScenario.payout(
        "payout-v1",
        new MockScenario.Delay(10, 20),
        rejection,
        loss,
        BigDecimal.ZERO,
        new MockScenario.Delay(0, 0),
        seed,
        NOW);
  }
}
