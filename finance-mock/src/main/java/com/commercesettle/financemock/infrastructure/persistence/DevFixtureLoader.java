package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.application.service.MockPayoutService;
import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/** 별도 실험 재원으로 기존 기본 계정·거래의 잔액을 보존한다. 금융 효과는 모의 지급 서비스가 생성한다. */
@Component
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class DevFixtureLoader {
  public static final String ACCOUNT = "fixture-payout-funding";
  private static final String[] SCENARIOS = {
    "fixture-normal-v1",
    "fixture-reject-v1",
    "fixture-response-loss-v1",
    "fixture-lookup-failure-v1"
  };
  private final JdbcTemplate jdbc;
  private final TransactionOperations transactions;
  private final MockAccountRepository accounts;
  private final MockScenarioRepository scenarios;
  private final MockPayoutService payouts;

  public DevFixtureLoader(
      JdbcTemplate jdbc,
      TransactionOperations transactions,
      MockAccountRepository accounts,
      MockScenarioRepository scenarios,
      MockPayoutService payouts) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.accounts = accounts;
    this.scenarios = scenarios;
    this.payouts = payouts;
  }

  public void load() {
    transactions.executeWithoutResult(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '5s'");
          jdbc.queryForList("SELECT pg_advisory_xact_lock(900903)");
          Instant at = Instant.parse("2026-09-01T00:00:00Z");
          if (accounts
              .find(MockScenario.PAYOUT_PROVIDER, ACCOUNT, AccountPurpose.PAYOUT)
              .isEmpty()) {
            accounts.save(
                MockAccount.open(
                    MockScenario.PAYOUT_PROVIDER, ACCOUNT, AccountPurpose.PAYOUT, 1_000_000, at));
          }
          for (int i = 0; i < SCENARIOS.length; i++) {
            if (scenarios.find(MockScenario.PAYOUT_PROVIDER, SCENARIOS[i]).isPresent()) continue;
            scenarios.save(
                MockScenario.payout(
                    SCENARIOS[i],
                    new MockScenario.Delay(0, 0),
                    i == 1 ? BigDecimal.ONE : BigDecimal.ZERO,
                    i == 2 ? BigDecimal.ONE : BigDecimal.ZERO,
                    i == 3 ? BigDecimal.ONE : BigDecimal.ZERO,
                    new MockScenario.Delay(0, 0),
                    20261030,
                    at));
          }
        });
  }

  /** 각 호출은 독립 커밋한다. 일부 실행 뒤 중단돼도 고정 업무 키로 이중 차감 없이 재개한다. */
  public void runScenarios() {
    for (int i = 0; i < SCENARIOS.length; i++) {
      payouts.execute(
          key(i),
          ACCOUNT,
          "mock-seller-00000000-0000-4000-8000-000000000002",
          19_550,
          SCENARIOS[i]);
    }
  }

  public static UUID key(int index) {
    return UUID.nameUUIDFromBytes(
        ("dev-fixture-v1:mock-payout-" + index).getBytes(StandardCharsets.UTF_8));
  }
}
