package com.commercesettle.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementEntryTest {
  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-01T03:00:00.123456789Z");
  private static final Instant NOW = Instant.parse("2026-10-01T03:00:01Z");

  @Test
  @DisplayName("최초 원장은 0원 leg를 포함한 5 leg이고 SOURCE·ALLOCATION 합계가 모두 B다")
  void initialEntriesBalanceToSettlementBase() {
    // E=8,000 M=0 P=2,000 → T=8,000, B=10,000, 수수료 1,500, 판매자 몫 8,500
    SettlementItem item = item(new SettlementAmounts(8_000, 2_000, 10_000, 8_000, 0, 1_500, 8_500));

    List<SettlementEntry> entries = SettlementEntry.initial(item, EVENT_ID, OCCURRED_AT, "v1", NOW);

    assertThat(entries)
        .extracting(SettlementEntry::getLeg, SettlementEntry::getAmount)
        .containsExactly(
            tuple(EntryLeg.EXTERNAL, 8_000L),
            tuple(EntryLeg.MILEAGE, 0L),
            tuple(EntryLeg.PLATFORM_COUPON, 2_000L),
            tuple(EntryLeg.SELLER, 8_500L),
            tuple(EntryLeg.FEE, 1_500L));
    assertThat(sum(entries, EntrySide.SOURCE)).isEqualTo(10_000);
    assertThat(sum(entries, EntrySide.ALLOCATION)).isEqualTo(10_000);
  }

  @Test
  @DisplayName("모든 leg가 항목·결제·원천 이벤트를 공유하고 원천 시각을 마이크로초로 보존한다")
  void entriesShareSourceAndItem() {
    SettlementItem item =
        item(new SettlementAmounts(10_000, 0, 10_000, 7_000, 3_000, 1_500, 8_500));

    List<SettlementEntry> entries = SettlementEntry.initial(item, EVENT_ID, OCCURRED_AT, "v1", NOW);

    assertThat(entries)
        .allSatisfy(
            entry -> {
              assertThat(entry.getItemId()).isEqualTo(item.getId());
              assertThat(entry.getPaymentId()).isEqualTo(item.getPaymentId());
              assertThat(entry.getBusinessRef()).isEqualTo(item.getPaymentId());
              assertThat(entry.getSourceEventId()).isEqualTo(EVENT_ID);
              assertThat(entry.getSourceOccurredAt())
                  .isEqualTo(Instant.parse("2026-10-01T03:00:00.123456Z"));
              assertThat(entry.getSide()).isEqualTo(entry.getLeg().side());
              assertThat(entry.getReversalOf()).isNull();
              assertThat(entry.getPolicyVersion()).isEqualTo("v1");
            });
    assertThat(entries).extracting(SettlementEntry::getId).doesNotHaveDuplicates();
  }

  private static SettlementItem item(SettlementAmounts amounts) {
    return SettlementItem.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        amounts,
        1,
        NOW);
  }

  private static long sum(List<SettlementEntry> entries, EntrySide side) {
    return entries.stream()
        .filter(entry -> entry.getSide() == side)
        .mapToLong(SettlementEntry::getAmount)
        .sum();
  }
}
