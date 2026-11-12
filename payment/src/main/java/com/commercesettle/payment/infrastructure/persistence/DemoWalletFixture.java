package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 시연 계정(구매자 A/B)의 마일리지 지갑만 만든다. 금융 스냅샷({@link DevFixtureLoader})과 분리해 계정 fixture와 함께 켠다. 없는 지갑만 만들고
 * 기존 지갑의 잔액·예약·원장은 바꾸지 않는다. 사용한 잔액을 재기동으로 다시 채우지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.dev-fixtures.demo-wallets", havingValue = "true")
public class DemoWalletFixture {
  /** Commerce 계정 fixture의 구매자 ID와 초기 잔액. 금융 스냅샷의 같은 구매자 지갑과 금액이 같다. */
  public static final Map<UUID, Long> WALLETS =
      Map.of(
          UUID.fromString("00000000-0000-4000-8000-000000000001"), 100_000L,
          UUID.fromString("00000000-0000-4000-8000-000000000011"), 0L);

  private static final Instant SEEDED_AT = Instant.parse("2026-09-01T00:00:00Z");
  private final JdbcTemplate jdbc;
  private final TransactionOperations transactions;
  private final MileageWalletRepository wallets;
  private final MileageEntryRepository entries;

  public DemoWalletFixture(
      JdbcTemplate jdbc,
      TransactionOperations transactions,
      MileageWalletRepository wallets,
      MileageEntryRepository entries) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.wallets = wallets;
    this.entries = entries;
  }

  public void load() {
    transactions.executeWithoutResult(
        tx -> {
          // 금융 스냅샷 fixture와 같은 잠금으로 중복 API 기동·두 fixture의 지갑 생성을 직렬화한다.
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          jdbc.queryForList("SELECT pg_advisory_xact_lock(900901)");
          WALLETS.forEach(this::openIfAbsent);
        });
  }

  private void openIfAbsent(UUID buyer, long balance) {
    if (wallets.findByBuyerIdForUpdate(buyer).isPresent()) return;
    MileageWallet wallet = wallets.save(MileageWallet.open(buyer, balance, SEEDED_AT));
    if (balance > 0) entries.save(MileageEntry.seed(wallet, SEEDED_AT));
  }
}
