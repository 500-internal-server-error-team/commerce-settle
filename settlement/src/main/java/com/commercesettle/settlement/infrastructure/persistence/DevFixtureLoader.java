package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 결제 확정 스냅샷에 대응하는 정산 항목. 환불 상태는 SAGA 실행과 별개인 도메인 예제다. */
@Component
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class DevFixtureLoader {
  private final JdbcTemplate jdbc;
  private final SettlementItemRepository items;

  public DevFixtureLoader(JdbcTemplate jdbc, SettlementItemRepository items) {
    this.jdbc = jdbc;
    this.items = items;
  }

  @Transactional
  public void load() {
    jdbc.execute("SET LOCAL lock_timeout = '5s'");
    jdbc.queryForList("SELECT pg_advisory_xact_lock(900902)");
    Instant at = Instant.parse("2026-09-01T00:00:00Z");
    Instant cutoff = at.plusSeconds(3600);
    for (int i = 0; i < 8; i++) {
      UUID payment = id("payment-settlement-" + i);
      if (items.findByPaymentId(payment).isPresent()) continue;
      boolean secondSeller = i == 4 || i == 5;
      long gross = secondSeller ? 39_000 : 23_000;
      long fee = gross * 15 / 100;
      SettlementItem item =
          SettlementItem.create(
              payment,
              id("order-settlement-" + i),
              account(secondSeller ? 23 : 3),
              account(secondSeller ? 22 : 2),
              new SettlementAmounts(gross, 0, gross, gross, 0, fee, gross - fee),
              0,
              at);
      if (i != 5) {
        Instant ready =
            i == 2 ? cutoff : i == 3 ? cutoff.plusNanos(1_000) : cutoff.minusNanos(1_000);
        item.markPayable(ready, at, ready);
      }
      if (i >= 6) item.reserveRefund(cutoff);
      if (i == 7) item.completeRefund(cutoff);
      items.save(item);
    }
  }

  public Set<UUID> itemIds() {
    return IntStream.range(0, 8)
        .mapToObj(i -> items.findByPaymentId(id("payment-settlement-" + i)).orElseThrow().getId())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(("dev-fixture-v1:" + name).getBytes(StandardCharsets.UTF_8));
  }

  private static UUID account(int suffix) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", suffix));
  }
}
