package com.commercesettle.financemock.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 지급 대역의 멱등·자금 선점·차감·해제와 응답 유실·조회 장애를 HTTP 경계로 확인한다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      // 인라인 속성은 ':'도 구분자로 읽으므로 발신자 ID의 ':'를 이스케이프한다.
      "app.internal-auth.senders[service\\:settlement].current="
          + MockPayoutIntegrationTest.SETTLEMENT,
      "app.internal-auth.senders[service\\:payment].current="
          + "test-payment-credential-0123456789abcdef0123456"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MockPayoutIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  static final String SETTLEMENT = "settlement-credential-0123456789abcdef0123456";
  // Gateway가 다른 서비스에 보내는 값이거나 다른 대상의 자격이다. 대역은 이 값을 모른다.
  private static final String GATEWAY = "payment-gateway-credential-0123456789abcdef01";
  private static final String OTHER_TARGET = "commerce-settlement-credential-0123456789abc";
  private static final long OPENING_BALANCE = 1_000_000;
  private static final String SELLER_ACCOUNT = "seller-account-1";

  @Autowired java.time.Clock clock;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockAccountRepository accounts;
  @Autowired MockScenarioRepository scenarios;

  @Test
  @DisplayName("대조 조회는 마감·기준선을 유지하고 실제 차감 기록과 소유 권한을 확인한다")
  void readsCashEvidenceAtFixedBoundary() throws Exception {
    mvc.perform(
            get("/mock/payout-reconciliation")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT)
                .param("cutoffAt", Instant.now().plusSeconds(60).toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RECONCILIATION_CUTOFF_NOT_READY"));
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    UUID key = UUID.randomUUID();
    payout(key, account.getAccountRef(), 30000, scenario).andExpect(status().isAccepted());
    Instant cutoff = clock.instant().plusSeconds(10);
    mvc.perform(
            get("/mock/payout-reconciliation")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT)
                .param("cutoffAt", cutoff.toString()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].businessKey").value(org.hamcrest.Matchers.hasItem(key.toString())))
        .andExpect(
            jsonPath("$.items[?(@.businessKey == '" + key + "')].debitCount")
                .value(org.hamcrest.Matchers.hasItem(1)))
        .andExpect(
            jsonPath("$.items[?(@.businessKey == '" + key + "')].debitAmount")
                .value(org.hamcrest.Matchers.hasItem(-30000)));
    mvc.perform(
            get("/mock/payout-reconciliation")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer test-payment-credential-0123456789abcdef0123456")
                .param("cutoffAt", cutoff.toString()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/mock/reconciliation-integrity")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checks.fundingBalance").value(0));
    jdbc.update(
        "UPDATE finance_mock.mock_accounts SET balance=balance-1 WHERE id=?", account.getId());
    mvc.perform(
            get("/mock/reconciliation-integrity")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checks.fundingBalance").value(1));
    jdbc.update(
        "UPDATE finance_mock.mock_accounts SET balance=balance+1 WHERE id=?", account.getId());
  }

  @Test
  @DisplayName("잔액 합계가 맞아도 잘못된 현금 종류의 부모 참조를 탐지한다")
  void detectsWrongCashKindWhenFundingBalanceMatches() throws Exception {
    var account = account(OPENING_BALANCE);
    UUID key = UUID.randomUUID();
    payout(
            key,
            account.getAccountRef(),
            30000,
            scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
        .andExpect(status().isAccepted());
    UUID transaction =
        jdbc.queryForObject(
            "SELECT id FROM finance_mock.mock_transactions WHERE business_key=?", UUID.class, key);
    long errors = 0;
    long adjustment = 0;
    var fixtures = new java.util.ArrayList<UUID>();
    try {
      for (String kind : List.of("PG_RECEIPT", "REFUND_DEBIT")) {
        long amount = "PG_RECEIPT".equals(kind) ? 1 : -1;
        UUID fixture = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO finance_mock.mock_cash_entries(id,account_id,kind,transaction_id,amount,created_at) VALUES(?,?,?,?,?,?)",
            fixture,
            account.getId(),
            kind,
            transaction,
            amount,
            clock.instant().atOffset(java.time.ZoneOffset.UTC));
        fixtures.add(fixture);
        jdbc.update(
            "UPDATE finance_mock.mock_accounts SET balance=balance+? WHERE id=?",
            amount,
            account.getId());
        adjustment += amount;
        mvc.perform(
                get("/mock/reconciliation-integrity")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checks.fundingBalance").value(0))
            .andExpect(jsonPath("$.checks.successfulCash").value(0))
            .andExpect(jsonPath("$.checks.cashReference").value(++errors));
      }
    } finally {
      // 정상 지급 원장은 보존하고 이 시험이 추가한 잘못된 fixture만 제거한다.
      for (UUID fixture : fixtures)
        jdbc.update("DELETE FROM finance_mock.mock_cash_entries WHERE id=?", fixture);
      jdbc.update(
          "UPDATE finance_mock.mock_accounts SET balance=balance-? WHERE id=?",
          adjustment,
          account.getId());
    }
  }

  @Test
  @DisplayName("성공한 지급은 재원 잔액에서 한 번 차감하고 현금 기록과 소비된 선점을 남긴다")
  void succeededPayoutDebitsFundingAccountOnce() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    UUID key = UUID.randomUUID();

    payout(key, account.getAccountRef(), 30_000, scenario)
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/mock/payouts/" + key))
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.operation").value("PAYOUT"))
        .andExpect(jsonPath("$.providerId").value("PAYOUT"))
        .andExpect(jsonPath("$.amount").value(30_000))
        .andExpect(jsonPath("$.succeededAt").isNotEmpty())
        .andExpect(jsonPath("$.fundingAccountRef").value(account.getAccountRef()))
        .andExpect(jsonPath("$.pgReceivedAmount").isEmpty());

    assertFunds(account, OPENING_BALANCE - 30_000, 0);
    assertThat(cashEntries(account)).containsExactly(-30_000L);
    assertThat(reservationStatuses(account)).containsExactly("CONSUMED");
  }

  @Test
  @DisplayName("같은 업무 키와 본문으로 다시 요청하면 같은 거래를 돌려주고 다시 차감하지 않는다")
  void replaysSameBusinessKey() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    UUID key = UUID.randomUUID();
    String firstId = transactionId(payout(key, account.getAccountRef(), 30_000, scenario));

    String replayId = transactionId(payout(key, account.getAccountRef(), 30_000, scenario));

    assertThat(replayId).isEqualTo(firstId);
    assertFunds(account, OPENING_BALANCE - 30_000, 0);
    assertThat(cashEntries(account)).containsExactly(-30_000L);
  }

  @Test
  @DisplayName("같은 업무 키에 다른 금액이 오면 409로 거절하고 금융 효과를 만들지 않는다")
  void rejectsSameBusinessKeyWithDifferentAmount() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    UUID key = UUID.randomUUID();
    payout(key, account.getAccountRef(), 30_000, scenario);

    payout(key, account.getAccountRef(), 31_000, scenario)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_CONFLICT"));

    assertFunds(account, OPENING_BALANCE - 30_000, 0);
    assertThat(transactionCount(key)).isEqualTo(1);
  }

  @Test
  @DisplayName("확정 거절이면 선점을 해제하고 잔액과 현금 기록은 그대로 둔다")
  void declinedPayoutReleasesReservation() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);

    payout(UUID.randomUUID(), account.getAccountRef(), 30_000, scenario)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("DECLINED"))
        .andExpect(jsonPath("$.succeededAt").isEmpty());

    assertFunds(account, OPENING_BALANCE, 0);
    assertThat(cashEntries(account)).isEmpty();
    assertThat(reservationStatuses(account)).containsExactly("RELEASED");
  }

  @Test
  @DisplayName("응답 유실이면 결과를 저장한 뒤 504를 돌려주고, 조회와 재요청은 같은 원결과를 돌려준다")
  void responseLossKeepsStoredResult() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO);
    UUID key = UUID.randomUUID();

    payout(key, account.getAccountRef(), 30_000, scenario)
        .andExpect(status().isGatewayTimeout())
        .andExpect(content().string(""));

    String lookedUpId =
        transactionId(
            lookup(key, scenario)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED")));
    String replayId = transactionId(payout(key, account.getAccountRef(), 30_000, scenario));
    assertThat(replayId).isEqualTo(lookedUpId);
    assertFunds(account, OPENING_BALANCE - 30_000, 0);
    assertThat(cashEntries(account)).containsExactly(-30_000L);
  }

  @Test
  @DisplayName("조회 장애와 시나리오 부재는 503, 거래 부재만 404로 구분한다")
  void distinguishesLookupFailureFromMissingTransaction() throws Exception {
    String normal = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    String failing = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
    UUID key = UUID.randomUUID();

    lookup(key, normal)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MOCK_TRANSACTION_NOT_FOUND"));
    lookup(key, failing)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MOCK_LOOKUP_FAILED"));
    lookup(key, "missing-scenario")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MOCK_SCENARIO_UNAVAILABLE"));
  }

  @Test
  @DisplayName("재원이 부족하면 시험 준비 오류로 끝내고 거래·멱등 기록을 남기지 않는다")
  void insufficientFundsLeavesNoTransaction() throws Exception {
    MockAccount account = account(10_000);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    UUID key = UUID.randomUUID();

    payout(key, account.getAccountRef(), 10_001, scenario)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("MOCK_FUNDS_NOT_PREPARED"));

    assertFunds(account, 10_000, 0);
    assertThat(transactionCount(key)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.idempotency_records r WHERE NOT EXISTS"
                    + " (SELECT 1 FROM finance_mock.mock_transactions t WHERE t.id = r.resource_id)",
                Long.class))
        .isZero();
  }

  @Test
  @DisplayName("등록되지 않은 재원 계정과 잘못된 금액은 400으로 거절한다")
  void rejectsInvalidRequests() throws Exception {
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    payout(UUID.randomUUID(), "unknown-account", 1_000, scenario)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("accountRef"));
    payout(UUID.randomUUID(), "unknown-account", 0, scenario)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("amount"));
  }

  @Test
  @DisplayName("정수 금액에 소수를 보내면 400으로 거절하고 거래·선점·차감을 만들지 않는다")
  void rejectsFractionalAmount() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    for (String amount : List.of("1000.9", "1000.0")) {
      UUID key = UUID.randomUUID();
      payout(key, account.getAccountRef(), amount, scenario).andExpect(status().isBadRequest());
      assertThat(transactionCount(key)).isZero();
    }
    assertFunds(account, OPENING_BALANCE, 0);
    assertThat(cashEntries(account)).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("응답 지연은 결과를 확정한 뒤에 주입해, 응답을 기다리는 동안에도 거래는 이미 확정돼 있다")
  void delaysResponseAfterStoringResult() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(new MockScenario.Delay(3_000, 3_000), BigDecimal.ZERO);
    UUID key = UUID.randomUUID();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> response =
          executor.submit(
              () ->
                  payout(key, account.getAccountRef(), 30_000, scenario)
                      .andReturn()
                      .getResponse()
                      .getStatus());

      String stored = null;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!"SUCCEEDED".equals(stored) && System.nanoTime() < deadline) {
        Thread.sleep(50);
        stored = transactionStatus(key);
      }

      assertThat(stored).isEqualTo("SUCCEEDED");
      assertThat(response.isDone()).isFalse();
      assertThat(response.get(20, TimeUnit.SECONDS)).isEqualTo(202);
    } finally {
      executor.shutdownNow();
    }
    assertFunds(account, OPENING_BALANCE - 30_000, 0);
  }

  @Test
  @Timeout(30)
  @DisplayName("같은 업무 키의 동시 요청도 거래 하나와 차감 한 번만 만든다")
  void concurrentSameBusinessKeyDebitsOnce() throws Exception {
    MockAccount account = account(OPENING_BALANCE);
    String scenario = scenario(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    UUID key = UUID.randomUUID();
    int requests = 6;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(requests);
    List<Future<Integer>> results = new ArrayList<>();
    try {
      for (int i = 0; i < requests; i++) {
        Callable<Integer> call =
            () -> {
              start.await();
              return payout(key, account.getAccountRef(), 30_000, scenario)
                  .andReturn()
                  .getResponse()
                  .getStatus();
            };
        results.add(executor.submit(call));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get(20, TimeUnit.SECONDS)).isIn(202, 409);
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(transactionCount(key)).isEqualTo(1);
    assertFunds(account, OPENING_BALANCE - 30_000, 0);
    assertThat(cashEntries(account)).containsExactly(-30_000L);
  }

  @ParameterizedTest(name = "{0} {1} → {3}")
  @CsvSource({
    "POST,/mock/payouts,'',401,COMMON_AUTHENTICATION_REQUIRED",
    "POST,/mock/payouts," + GATEWAY + ",401,COMMON_AUTHENTICATION_REQUIRED",
    "GET,/mock/payouts/00000000-0000-4000-8000-000000000001,"
        + OTHER_TARGET
        + ",401,COMMON_AUTHENTICATION_REQUIRED",
    "POST,/mock/accounts," + SETTLEMENT + ",403,COMMON_ROLE_FORBIDDEN",
    "GET,/mock/unregistered," + SETTLEMENT + ",404,COMMON_RESOURCE_NOT_FOUND"
  })
  @DisplayName("Settlement 자격이 아니거나 자기 작업이 아니면 대역 처리 전에 공통 오류로 거절한다")
  void rejectsCallerWithoutSettlementCredential(
      String method, String path, String credential, int status, String code) throws Exception {
    MockHttpServletRequestBuilder request = "POST".equals(method) ? post(path) : get(path);
    if (!credential.isEmpty()) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
    }

    mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().is(status))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.instance").value(path));
  }

  private ResultActions payout(UUID key, String accountRef, long amount, String scenario)
      throws Exception {
    return payout(key, accountRef, Long.toString(amount), scenario);
  }

  /** 금액을 JSON 숫자 원문으로 보낸다. */
  private ResultActions payout(UUID key, String accountRef, String amount, String scenario)
      throws Exception {
    String body =
        """
        {"businessKey":"%s","accountRef":"%s","sellerAccountRef":"%s",\
        "amount":%s,"scenarioVersion":"%s"}"""
            .formatted(key, accountRef, SELLER_ACCOUNT, amount, scenario);
    return mvc.perform(
        post("/mock/payouts")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT)
            .header("Idempotency-Key", "payout-" + key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions lookup(UUID key, String scenario) throws Exception {
    return mvc.perform(
        get("/mock/payouts/{key}", key)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + SETTLEMENT)
            .param("scenarioVersion", scenario));
  }

  private static String transactionId(ResultActions result) throws Exception {
    String json = result.andReturn().getResponse().getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  private MockAccount account(long openingBalance) {
    return accounts.save(
        MockAccount.open(
            "PAYOUT",
            "funding-" + UUID.randomUUID(),
            AccountPurpose.PAYOUT,
            openingBalance,
            clock.instant()));
  }

  private String scenario(BigDecimal rejection, BigDecimal loss, BigDecimal lookupFailure) {
    return scenario(new MockScenario.Delay(0, 0), rejection, loss, lookupFailure);
  }

  private String scenario(MockScenario.Delay delay, BigDecimal rejection) {
    return scenario(delay, rejection, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  private String scenario(
      MockScenario.Delay delay, BigDecimal rejection, BigDecimal loss, BigDecimal lookupFailure) {
    String version = "t-" + UUID.randomUUID().toString().substring(0, 18);
    scenarios.save(
        MockScenario.payout(
            version,
            delay,
            rejection,
            loss,
            lookupFailure,
            new MockScenario.Delay(0, 0),
            1L,
            clock.instant()));
    return version;
  }

  private void assertFunds(MockAccount account, long balance, long reserved) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT balance, reserved_amount FROM finance_mock.mock_accounts WHERE id = ?",
            account.getId());
    assertThat(row).containsEntry("balance", balance).containsEntry("reserved_amount", reserved);
  }

  private List<Long> cashEntries(MockAccount account) {
    return jdbc.queryForList(
        "SELECT amount FROM finance_mock.mock_cash_entries WHERE account_id = ? AND kind = 'PAYOUT_DEBIT'",
        Long.class,
        account.getId());
  }

  private List<String> reservationStatuses(MockAccount account) {
    return jdbc.queryForList(
        "SELECT status FROM finance_mock.mock_funds_reservations WHERE account_id = ?",
        String.class,
        account.getId());
  }

  private String transactionStatus(UUID key) {
    return jdbc
        .queryForList(
            "SELECT status FROM finance_mock.mock_transactions WHERE business_key = ?",
            String.class,
            key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private long transactionCount(UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM finance_mock.mock_transactions WHERE business_key = ?",
        Long.class,
        key);
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class ReconciliationClock {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    java.time.Clock fixedClock() {
      return java.time.Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), java.time.ZoneOffset.UTC);
    }
  }
}
