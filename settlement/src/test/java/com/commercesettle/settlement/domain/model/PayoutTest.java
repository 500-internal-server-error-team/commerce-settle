package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.domain.exception.InvalidPayoutTransitionException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayoutTest {
  private static final Instant NOW = Instant.parse("2026-10-30T01:00:00Z");
  private static final Instant SUCCEEDED_AT = Instant.parse("2026-10-30T01:00:03.1234567Z");

  @Test
  @DisplayName("처리 중인 지급은 대역의 최초 성공 시각을 그대로 남기고 보류 사유를 지운다")
  void succeedCopiesProviderSuccessTime() {
    Payout payout = processing();
    payout.markUnknown(NOW);
    UUID externalRef = UUID.randomUUID();

    payout.succeed(SUCCEEDED_AT, externalRef, NOW.plusSeconds(5));

    assertThat(payout.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
    assertThat(payout.getSucceededAt()).isEqualTo(Instant.parse("2026-10-30T01:00:03.123456Z"));
    assertThat(payout.getExternalRef()).isEqualTo(externalRef);
    assertThat(payout.getHoldReason()).isNull();
  }

  @Test
  @DisplayName("확정 거절은 보류 사유를 확정 거절로 남긴다")
  void declineRecordsConfirmedRejection() {
    Payout payout = processing();

    payout.decline(UUID.randomUUID(), NOW);

    assertThat(payout.getStatus()).isEqualTo(PayoutStatus.DECLINED);
    assertThat(payout.getHoldReason()).isEqualTo(PayoutHoldReason.CONFIRMED_REJECTION);
    assertThat(payout.getSucceededAt()).isNull();
  }

  @Test
  @DisplayName("호출 전 지급은 결과를 받을 수 없고, 확정된 지급은 다시 확정할 수 없다")
  void rejectsResultOutsideFlight() {
    Payout pending = pending();
    Payout succeeded = processing();
    succeeded.succeed(SUCCEEDED_AT, UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> pending.succeed(SUCCEEDED_AT, UUID.randomUUID(), NOW))
        .isInstanceOf(InvalidPayoutTransitionException.class);
    assertThatThrownBy(() -> succeeded.decline(UUID.randomUUID(), NOW))
        .isInstanceOf(InvalidPayoutTransitionException.class);
    assertThatThrownBy(() -> succeeded.markUnknown(NOW))
        .isInstanceOf(InvalidPayoutTransitionException.class);
    assertThat(succeeded.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
  }

  @Test
  @DisplayName("배치는 처리 중일 때만 완료하고, 완료한 배치는 운영 확인으로 돌리지 않는다")
  void batchCompletesOnlyFromProcessing() {
    SettlementBatch batch =
        SettlementBatch.reserve(
            UUID.randomUUID(), LocalDate.of(2026, 10, 29), "delivery-daily-v1", 1_000, NOW);

    assertThatThrownBy(() -> batch.complete(NOW))
        .isInstanceOf(InvalidPayoutTransitionException.class);
    batch.startProcessing(NOW);
    batch.complete(NOW);
    assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
    assertThatThrownBy(() -> batch.requireReview(NOW))
        .isInstanceOf(InvalidPayoutTransitionException.class);
  }

  private static Payout processing() {
    Payout payout = pending();
    payout.startProcessing(NOW);
    return payout;
  }

  private static Payout pending() {
    return Payout.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PayoutRecipient("seller-account", 1),
        "payout-funding-default",
        "payout-normal-v1",
        25_500,
        NOW);
  }
}
