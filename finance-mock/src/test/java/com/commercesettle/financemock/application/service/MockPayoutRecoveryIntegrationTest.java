package com.commercesettle.financemock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.application.exception.TransactionNotFoundException;
import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.FundsReservation;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.model.MockTransactionStatus;
import com.commercesettle.financemock.domain.repository.CashEntryRepository;
import com.commercesettle.financemock.domain.repository.FundsReservationRepository;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import com.commercesettle.financemock.domain.repository.MockTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 지급 접수·확정이 한 트랜잭션이라 중간 상태가 커밋되지 않고, 조회는 저장된 결과만 돌려주는지 실제 PostgreSQL에서 확인한다. */
@SpringBootTest(properties = "management.tracing.enabled=false")
@Testcontainers
class MockPayoutRecoveryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  private static final long OPENING_BALANCE = 1_000_000;
  private static final long AMOUNT = 30_000;

  @MockitoSpyBean CashEntryRepository cashEntries;
  @Autowired MockPayoutService payouts;
  @Autowired MockPayoutLookupService lookups;
  @Autowired MockAccountRepository accounts;
  @Autowired MockTransactionRepository transactions;
  @Autowired FundsReservationRepository reservations;
  @Autowired MockScenarioRepository scenarios;
  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("확정 저장이 실패하면 거래·선점이 남지 않아 조회는 404이고, 원키 재요청이 한 번만 차감한다")
  void failureBeforeCommitLeavesNothingAndResendSucceeds() {
    MockAccount account = account(0);
    String scenario = scenario();
    UUID key = UUID.randomUUID();
    doThrow(new DataAccessResourceFailureException("injected"))
        .doCallRealMethod()
        .when(cashEntries)
        .save(any());

    assertThatThrownBy(() -> execute(key, account, scenario))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThatThrownBy(() -> lookups.find(key, scenario))
        .isInstanceOf(TransactionNotFoundException.class);
    assertFunds(account, OPENING_BALANCE, 0);

    MockTransactionView resent = execute(key, account, scenario);

    assertThat(resent.transaction().getStatus()).isEqualTo(MockTransactionStatus.SUCCEEDED);
    assertFunds(account, OPENING_BALANCE - AMOUNT, 0);
  }

  @Test
  @DisplayName("PROCESSING 거래는 조회가 확정하지 않고 저장된 상태를 그대로 돌려준다")
  void lookupReturnsProcessingFixtureAsStored() {
    MockAccount account = account(AMOUNT);
    String scenario = scenario();
    UUID key = UUID.randomUUID();
    MockTransaction transaction =
        transactions.save(
            MockTransaction.acceptPayout(
                key, account.getId(), AMOUNT, scenario, 1L, Instant.now()));
    reservations.save(
        FundsReservation.reserve(account.getId(), transaction.getId(), AMOUNT, Instant.now()));

    assertThat(lookups.find(key, scenario).transaction().getStatus())
        .isEqualTo(MockTransactionStatus.PROCESSING);
    assertThat(lookups.find(key, scenario).transaction().getStatus())
        .isEqualTo(MockTransactionStatus.PROCESSING);
    assertFunds(account, OPENING_BALANCE, AMOUNT);
  }

  private MockTransactionView execute(UUID key, MockAccount account, String scenario) {
    return payouts
        .execute(key, account.getAccountRef(), "seller-account-1", AMOUNT, scenario)
        .view();
  }

  /** reserved만큼 이미 선점된 재원 계정을 만든다. */
  private MockAccount account(long reserved) {
    MockAccount account =
        MockAccount.open(
            "PAYOUT",
            "funding-" + UUID.randomUUID(),
            AccountPurpose.PAYOUT,
            OPENING_BALANCE,
            Instant.now());
    if (reserved > 0) {
      account.reserve(reserved, Instant.now());
    }
    return accounts.save(account);
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
