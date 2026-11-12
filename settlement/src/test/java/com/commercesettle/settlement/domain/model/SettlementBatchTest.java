package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementBatchTest {
  private static final LocalDate BATCH_DATE = LocalDate.of(2026, 10, 29);
  private static final Instant CUTOFF = Instant.parse("2026-10-29T01:00:00Z");

  @Test
  @DisplayName("마감은 배치 날짜의 10:00 Asia/Seoul이다")
  void cutoffIsTenOclockInSeoul() {
    assertThat(SettlementBatch.cutoffOf(BATCH_DATE)).isEqualTo(CUTOFF);
  }

  @Test
  @DisplayName("마감이 지난 날짜의 합계로 대상이 고정된 배치를 만든다")
  void reservesBatchAfterCutoff() {
    UUID sellerId = UUID.randomUUID();

    SettlementBatch batch =
        SettlementBatch.reserve(sellerId, BATCH_DATE, "delivery-daily-v1", 25_500, CUTOFF);

    assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.RESERVED);
    assertThat(batch.getCutoffAt()).isEqualTo(CUTOFF);
    assertThat(batch.getNetAmount()).isEqualTo(25_500);
    assertThat(batch.getSellerId()).isEqualTo(sellerId);
  }

  @Test
  @DisplayName("마감 전이거나 합계가 없으면 배치를 만들지 않는다")
  void rejectsBatchBeforeCutoffOrWithoutAmount() {
    UUID sellerId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                SettlementBatch.reserve(
                    sellerId, BATCH_DATE, "delivery-daily-v1", 1, CUTOFF.minusNanos(1_000)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SettlementBatch.reserve(sellerId, BATCH_DATE, "delivery-daily-v1", 0, CUTOFF))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("지급은 대기 상태로 만들고 지급 작업은 같은 업무 키로 바로 실행할 수 있게 둔다")
  void payoutJobSharesBusinessKey() {
    Payout payout =
        Payout.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new PayoutRecipient("seller-account", 3),
            "payout-funding-default",
            "payout-normal-v1",
            25_500,
            CUTOFF);

    PayoutJob job = PayoutJob.schedule(payout, CUTOFF);

    assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PENDING);
    assertThat(payout.getRecipientAccountVersion()).isEqualTo(3);
    assertThat(job.getBusinessKey()).isEqualTo(payout.getBusinessKey());
    assertThat(job.getStatus()).isEqualTo(PayoutJobStatus.PENDING);
    assertThat(job.getNextRunAt()).isEqualTo(CUTOFF);
    assertThat(job.getAttempts()).isZero();
  }
}
