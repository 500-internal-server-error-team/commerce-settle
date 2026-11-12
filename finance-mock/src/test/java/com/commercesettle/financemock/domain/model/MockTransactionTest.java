package com.commercesettle.financemock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.financemock.domain.exception.InvalidMockTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockTransactionTest {
  private static final Instant ACCEPTED_AT = Instant.parse("2026-10-30T01:00:00Z");
  private static final Instant SUCCEEDED_AT = Instant.parse("2026-10-30T01:00:01.1234567Z");

  @Test
  @DisplayName("지급은 처리 중으로 접수되고 성공하면 최초 성공 시각을 마이크로초로 남긴다")
  void recordsFirstSuccessTime() {
    MockTransaction transaction = payout();

    transaction.succeed(SUCCEEDED_AT);

    assertThat(transaction.getStatus()).isEqualTo(MockTransactionStatus.SUCCEEDED);
    assertThat(transaction.getSucceededAt())
        .isEqualTo(Instant.parse("2026-10-30T01:00:01.123456Z"));
  }

  @Test
  @DisplayName("확정된 거래는 다시 확정할 수 없고 최초 결과를 유지한다")
  void keepsFirstResult() {
    MockTransaction transaction = payout();
    transaction.succeed(SUCCEEDED_AT);

    assertThatThrownBy(() -> transaction.decline(SUCCEEDED_AT.plusSeconds(1)))
        .isInstanceOf(InvalidMockTransitionException.class);
    assertThatThrownBy(() -> transaction.succeed(SUCCEEDED_AT.plusSeconds(1)))
        .isInstanceOf(InvalidMockTransitionException.class);
    assertThat(transaction.getStatus()).isEqualTo(MockTransactionStatus.SUCCEEDED);
    assertThat(transaction.getSucceededAt())
        .isEqualTo(Instant.parse("2026-10-30T01:00:01.123456Z"));
  }

  @Test
  @DisplayName("확정 거절은 성공 시각을 남기지 않는다")
  void declineHasNoSuccessTime() {
    MockTransaction transaction = payout();

    transaction.decline(SUCCEEDED_AT);

    assertThat(transaction.getStatus()).isEqualTo(MockTransactionStatus.DECLINED);
    assertThat(transaction.getSucceededAt()).isNull();
  }

  private static MockTransaction payout() {
    MockTransaction transaction =
        MockTransaction.acceptPayout(
            UUID.randomUUID(), UUID.randomUUID(), 10_000, "payout-v1", 42L, ACCEPTED_AT);
    assertThat(transaction.getStatus()).isEqualTo(MockTransactionStatus.PROCESSING);
    return transaction;
  }
}
