package com.commercesettle.financemock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;

import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.application.dto.PayoutResult;
import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.MockTransactionStatus;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 접수 커밋 뒤 확정이 실패해 PROCESSING으로 멈춘 지급을 재요청·조회가 저장된 seed로 재개하는지 실제 PostgreSQL에서 확인한다. */
@SpringBootTest(properties = "management.tracing.enabled=false")
@Testcontainers
class MockPayoutRecoveryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  private static final long OPENING_BALANCE = 1_000_000;
  private static final long AMOUNT = 30_000;

  @MockitoSpyBean PayoutTransactions transactions;
  @Autowired MockPayoutService payouts;
  @Autowired MockPayoutLookupService lookups;
  @Autowired MockAccountRepository accounts;
  @Autowired MockScenarioRepository scenarios;
  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("확정이 실패해 멈춘 지급은 같은 키 재요청에서 확정하고 한 번만 차감한다")
  void replayResumesStuckPayout() {
    MockAccount account = account();
    String scenario = scenario();
    UUID key = UUID.randomUUID();
    stuck(key, account, scenario);

    MockTransactionView replayed = execute(key, account, scenario).view();

    assertThat(replayed.transaction().getStatus()).isEqualTo(MockTransactionStatus.SUCCEEDED);
    assertFunds(account, OPENING_BALANCE - AMOUNT, 0);
  }

  @Test
  @DisplayName("확정이 실패해 멈춘 지급은 재요청 없이 조회만 해도 확정하고 한 번만 차감한다")
  void lookupResumesStuckPayout() {
    MockAccount account = account();
    String scenario = scenario();
    UUID key = UUID.randomUUID();
    stuck(key, account, scenario);

    MockTransactionView found = lookups.find(key, scenario);

    assertThat(found.transaction().getStatus()).isEqualTo(MockTransactionStatus.SUCCEEDED);
    assertThat(lookups.find(key, scenario).transaction().getStatus())
        .isEqualTo(MockTransactionStatus.SUCCEEDED);
    assertFunds(account, OPENING_BALANCE - AMOUNT, 0);
  }

  @Test
  @DisplayName("이미 확정된 거래를 다시 확정해도 상태와 잔액이 바뀌지 않는다")
  void completingFinishedPayoutAgainChangesNothing() {
    MockAccount account = account();
    String scenario = scenario();
    UUID id = execute(UUID.randomUUID(), account, scenario).view().transaction().getId();

    MockTransactionView again = transactions.complete(id, true);

    assertThat(again.transaction().getStatus()).isEqualTo(MockTransactionStatus.SUCCEEDED);
    assertFunds(account, OPENING_BALANCE - AMOUNT, 0);
  }

  /** 접수는 커밋되고 확정만 한 번 실패한 상태를 만든다. */
  private void stuck(UUID key, MockAccount account, String scenario) {
    doThrow(new CannotAcquireLockException("injected"))
        .doCallRealMethod()
        .when(transactions)
        .complete(any(), anyBoolean());
    assertThatThrownBy(() -> execute(key, account, scenario))
        .isInstanceOf(CannotAcquireLockException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM finance_mock.mock_transactions WHERE business_key = ?",
                String.class,
                key))
        .isEqualTo("PROCESSING");
    assertFunds(account, OPENING_BALANCE, AMOUNT);
  }

  private PayoutResult execute(UUID key, MockAccount account, String scenario) {
    return payouts.execute(key, account.getAccountRef(), "seller-account-1", AMOUNT, scenario);
  }

  private MockAccount account() {
    return accounts.save(
        MockAccount.open(
            "PAYOUT",
            "funding-" + UUID.randomUUID(),
            AccountPurpose.PAYOUT,
            OPENING_BALANCE,
            Instant.now()));
  }

  private String scenario() {
    String version = "t-" + UUID.randomUUID().toString().substring(0, 18);
    scenarios.save(
        MockScenario.payout(
            version,
            new MockScenario.Delay(0, 0),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new MockScenario.Delay(0, 0),
            1L,
            Instant.now()));
    return version;
  }

  private void assertFunds(MockAccount account, long balance, long reserved) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT balance, reserved_amount FROM finance_mock.mock_accounts WHERE id = ?",
            account.getId());
    assertThat(row).containsEntry("balance", balance).containsEntry("reserved_amount", reserved);
  }
}
