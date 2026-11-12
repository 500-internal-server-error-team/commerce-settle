package com.commercesettle.financemock.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.commercesettle.financemock.application.service.MockPaymentService;
import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockPaymentMethod;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.model.PaymentProvider;
import com.commercesettle.financemock.domain.repository.CashEntryRepository;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import com.commercesettle.financemock.domain.repository.MockTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 HTTP·JPA·PostgreSQL 커밋 경계에서 원거래와 금액을 확인한다. 테스트 트랜잭션으로 감싸지 않는다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "finance-mock.pg.receipts-enabled=false",
      "finance-mock.pg.holidays=2026-11-03",
      "app.internal-auth.senders[service\\:settlement].current="
          + MockPaymentIntegrationTest.SETTLEMENT,
      "app.internal-auth.senders[service\\:payment].current=" + MockPaymentIntegrationTest.PAYMENT
    })
@Import(MockPaymentIntegrationTest.TimeConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(30)
class MockPaymentIntegrationTest {
  static final String SETTLEMENT = "test-settlement-credential-0123456789abcdef0123";
  static final String PAYMENT = "test-payment-credential-0123456789abcdef0123456";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00.123456789Z");
  private static final long AMOUNT = 30_000;
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired MockAccountRepository accounts;
  @Autowired MockScenarioRepository scenarios;
  @MockitoSpyBean MockTransactionRepository transactions;
  @Autowired MockPaymentService payments;
  @MockitoSpyBean CashEntryRepository cashEntries;
  MockAccount funding;
  MockAccount wallet;

  @BeforeEach
  void prepareAccounts() {
    funding =
        accounts.save(
            MockAccount.open(
                "PAYOUT", "f-" + UUID.randomUUID(), AccountPurpose.PAYOUT, 1_000_000, NOW));
    wallet =
        accounts.save(
            MockAccount.open(
                "EASY_PAY", "w-" + UUID.randomUUID(), AccountPurpose.WALLET, AMOUNT, NOW));
  }

  @Test
  @DisplayName("정상 PG 입금과 입금후 환불의 현금 참조는 소유 HTTP 대조와 일치한다")
  void reconcilesNormalReceiptAndRefundCash() throws Exception {
    var provider = PaymentProvider.PG_A;
    UUID original = approve(provider, scenario(provider, 0, 0, 0, 0, 0, 0, 0, 0));
    payments.receiveDue();
    assertThat(cash()).containsExactly(AMOUNT);
    json(refund(provider, original, UUID.randomUUID(), AMOUNT), 202);
    assertThat(cash()).containsExactlyInAnyOrder(AMOUNT, -AMOUNT);
    var result = json(get("/mock/reconciliation-integrity", SETTLEMENT), 200);
    assertThat(result.path("checks").path("cashReference").asLong()).isZero();
    assertThat(result.path("checks").path("successfulCash").asLong()).isZero();
    assertThat(result.path("checks").path("fundingBalance").asLong()).isZero();
  }

  @Test
  @DisplayName("PG의 승인·조회·환불은 Payment 자격만 허용하며 거절 요청은 금융 상태를 바꾸지 않는다")
  void rejectsOtherCallersWithoutFinancialEffects() throws Exception {
    var provider = PaymentProvider.EASY_PAY;
    String version = normal(provider);
    UUID key = UUID.randomUUID();
    var body = approval(key, provider, version);
    String lookupPath =
        "/mock/providers/EASY_PAY/transactions/"
            + key
            + "?operation=APPROVE&scenarioVersion="
            + version;
    int before =
        jdbc.queryForObject("SELECT count(*) FROM finance_mock.idempotency_records", Integer.class);
    for (String credential :
        new String[] {null, "unknown-credential-0123456789abcdef01234567890", SETTLEMENT}) {
      int expected = SETTLEMENT.equals(credential) ? 403 : 401;
      json(post(provider, "/payments", body, credential), expected);
      json(get(lookupPath, credential), expected);
    }
    assertThat(count(key)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.idempotency_records", Integer.class))
        .isEqualTo(before);
    assertBalance(wallet, AMOUNT);
    assertBalance(funding, 1_000_000);

    UUID original =
        UUID.fromString(json(post(provider, "/payments", body), 202).path("id").asText());
    UUID refundKey = UUID.randomUUID();
    before =
        jdbc.queryForObject("SELECT count(*) FROM finance_mock.idempotency_records", Integer.class);
    for (String credential :
        new String[] {null, "unknown-credential-0123456789abcdef01234567890", SETTLEMENT}) {
      json(
          post(
              provider,
              "/payments/" + original + "/refunds",
              Map.of("businessKey", refundKey, "amount", AMOUNT),
              credential),
          SETTLEMENT.equals(credential) ? 403 : 401);
    }
    var headers = new HttpHeaders();
    headers.setBearerAuth(PAYMENT);
    json(
        http.postForEntity("/mock/payouts", new HttpEntity<>(Map.of(), headers), String.class),
        403);
    assertThat(count(refundKey)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.idempotency_records", Integer.class))
        .isEqualTo(before);
    assertBalance(wallet, 0);
    assertBalance(funding, 1_000_000);
    assertThat(cash()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(PaymentProvider.class)
  @DisplayName("승인·원결과 조회·전액 환불을 연결하고 재요청·중복 환불에도 금액을 보존한다")
  void approvesLooksUpAndRefundsOnce(PaymentProvider provider) throws Exception {
    String version = scenario(provider, 0, 0, 0, 0, 0, 0, 0, 5);
    UUID key = UUID.randomUUID();
    var body = approval(key, provider, version);
    var approved = post(provider, "/payments", body);
    JsonNode original = json(approved, 202);
    UUID id = UUID.fromString(original.path("id").asText());
    assertThat(original.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(original.path("succeededAt").asText()).isEqualTo("2026-11-02T10:00:00.123456+09:00");
    assertThat(original.path("pgReceiptDueAt").asText())
        .isEqualTo("2026-11-10T10:00:00.123456+09:00");
    assertThat(original.path("pgReceivedAmount").isNull()).isTrue();
    assertThat(json(get(approved.getHeaders().getLocation().toString(), PAYMENT), 200))
        .isEqualTo(original);
    assertThat(json(post(provider, "/payments", body), 202)).isEqualTo(original);
    assertBalance(wallet, provider == PaymentProvider.EASY_PAY ? 0 : AMOUNT);
    assertBalance(funding, 1_000_000);

    UUID refundKey = UUID.randomUUID();
    var refund = refund(provider, id, refundKey, AMOUNT);
    var refunded = json(refund, 202);
    assertThat(refunded.path("originalId").asText()).isEqualTo(id.toString());
    assertThat(refunded.path("operation").asText()).isEqualTo("REFUND");
    assertThat(refunded.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(refunded.path("scenarioVersion").asText()).isEqualTo(version);
    assertThat(json(refund(provider, id, refundKey, AMOUNT), 202)).isEqualTo(refunded);
    json(refund(provider, id, UUID.randomUUID(), AMOUNT), 409);
    assertThat(
            json(lookup(provider, key, "APPROVE", version), 200).path("pgReceivedAmount").asLong())
        .isZero();
    payments.receiveDue();
    assertBalance(wallet, AMOUNT);
    assertBalance(funding, 1_000_000);
    assertThat(cash()).isEmpty();
    assertThat(count(key)).isEqualTo(1);
    assertThat(count(refundKey)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(PaymentProvider.class)
  @DisplayName("확정 거절은 저장해 재요청에도 유지하며 잔액과 입금 예정액을 만들지 않는다")
  void preservesDeclinedApproval(PaymentProvider provider) throws Exception {
    String version = scenario(provider, 1, 0, 0, 0, 0, 0, 0, 5);
    UUID key = UUID.randomUUID();
    var body = approval(key, provider, version);
    var declined = json(post(provider, "/payments", body), 202);
    assertThat(declined.path("status").asText()).isEqualTo("DECLINED");
    assertThat(declined.path("succeededAt").isNull()).isTrue();
    assertThat(declined.path("pgReceiptDueAt").isNull()).isTrue();
    assertThat(json(post(provider, "/payments", body), 202)).isEqualTo(declined);
    json(
        refund(provider, UUID.fromString(declined.path("id").asText()), UUID.randomUUID(), AMOUNT),
        409);
    assertBalance(wallet, AMOUNT);
    assertThat(cash()).isEmpty();
  }

  @Test
  @DisplayName("승인 본문의 금액·계정·수단·재원·금융 버전 변경은 헤더를 바꿔도 충돌한다")
  void rejectsChangedApprovalBody() throws Exception {
    var provider = PaymentProvider.PG_A;
    String version = normal(provider);
    UUID key = UUID.randomUUID();
    var body = approval(key, provider, version);
    json(post(provider, "/payments", body), 202);
    for (var change :
        Map.<String, Object>of(
                "amount",
                AMOUNT + 1,
                "accountRef",
                "other",
                "method",
                "DEBIT_CARD",
                "fundingAccountRef",
                "other",
                "scenarioVersion",
                "other")
            .entrySet()) {
      var changed = new HashMap<>(body);
      changed.put(change.getKey(), change.getValue());
      assertThat(json(post(provider, "/payments", changed), 409).path("code").asText())
          .isEqualTo("COMMON_IDEMPOTENCY_CONFLICT");
    }
    assertThat(count(key)).isEqualTo(1);
    assertBalance(funding, 1_000_000);
  }

  @ParameterizedTest
  @EnumSource(PaymentProvider.class)
  @DisplayName("승인·환불 성공 후 응답 유실은 조회와 원키 재요청으로 같은 결과를 확인한다")
  void recoversLostApprovalAndRefundResponse(PaymentProvider provider) throws Exception {
    String version = scenario(provider, 0, 1, 0, 1, 0, 0, 0, 0);
    UUID key = UUID.randomUUID();
    var body = approval(key, provider, version);
    assertThat(post(provider, "/payments", body).getStatusCode().value()).isEqualTo(504);
    var stored = json(lookup(provider, key, "APPROVE", version), 200);
    assertThat(stored.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(json(post(provider, "/payments", body), 202)).isEqualTo(stored);
    payments.receiveDue();
    assertBalance(funding, 1_030_000);
    UUID id = UUID.fromString(stored.path("id").asText());
    UUID refundKey = UUID.randomUUID();
    assertThat(refund(provider, id, refundKey, AMOUNT).getStatusCode().value()).isEqualTo(504);
    var refunded = json(lookup(provider, refundKey, "REFUND", version), 200);
    assertThat(refunded.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(json(refund(provider, id, refundKey, AMOUNT), 202)).isEqualTo(refunded);
    payments.receiveDue();
    assertBalance(funding, 1_000_000);
    assertBalance(wallet, AMOUNT);
    assertThat(cash()).containsExactlyInAnyOrder(30_000L, -30_000L);
  }

  @Test
  @DisplayName("환불 확정 거절은 원승인 입금·지갑을 바꾸지 않고 새 업무 키의 환불만 다시 시도할 수 있다")
  void keepsDeclinedRefundWithoutCompensation() throws Exception {
    var provider = PaymentProvider.EASY_PAY;
    String version = scenario(provider, 0, 0, 1, 0, 0, 0, 0, 0);
    UUID originalId = approve(provider, version);
    payments.receiveDue();
    UUID key = UUID.randomUUID();
    var declined = json(refund(provider, originalId, key, AMOUNT), 202);
    assertThat(declined.path("status").asText()).isEqualTo("DECLINED");
    assertThat(json(refund(provider, originalId, key, AMOUNT), 202)).isEqualTo(declined);
    assertBalance(wallet, 0);
    assertBalance(funding, 1_030_000);
    assertThat(cash()).containsExactly(30_000L);
  }

  @Test
  @DisplayName("조회 장애·설정 부재와 거래 부재를 구분하고 조회 버전·작업·대역을 금융 결과와 분리한다")
  void isolatesLookupProfilesAndNamespaces() throws Exception {
    var provider = PaymentProvider.PG_B;
    String version = normal(provider);
    String outage = scenario(provider, 0, 0, 0, 0, 1, 0, 0, 5);
    UUID key = UUID.randomUUID();
    var original = json(post(provider, "/payments", approval(key, provider, version)), 202);
    assertThat(json(lookup(provider, key, "APPROVE", outage), 503).path("code").asText())
        .isEqualTo("MOCK_LOOKUP_FAILED");
    assertThat(
            json(lookup(provider, UUID.randomUUID(), "APPROVE", outage), 503).path("code").asText())
        .isEqualTo("MOCK_LOOKUP_FAILED");
    assertThat(json(lookup(provider, key, "APPROVE", "missing"), 503).path("code").asText())
        .isEqualTo("MOCK_SCENARIO_UNAVAILABLE");
    json(lookup(provider, key, "REFUND", version), 404);
    json(lookup(PaymentProvider.PG_A, key, "APPROVE", normal(PaymentProvider.PG_A)), 404);
    assertThat(json(lookup(provider, key, "APPROVE", version), 200)).isEqualTo(original);
    json(lookup(provider, key, "PAYOUT", version), 400);
  }

  @Test
  @DisplayName("원승인이 아닌 거래·타 대역·부분 금액·다른 원거래의 멱등키 재사용을 거절한다")
  void validatesOriginalApprovalAndFullAmount() throws Exception {
    var provider = PaymentProvider.PG_A;
    String version = normal(provider);
    UUID originalId = approve(provider, version);
    json(refund(PaymentProvider.PG_B, originalId, UUID.randomUUID(), AMOUNT), 404);
    json(refund(provider, UUID.randomUUID(), UUID.randomUUID(), AMOUNT), 404);
    json(refund(provider, originalId, UUID.randomUUID(), AMOUNT - 1), 409);
    UUID key = UUID.randomUUID();
    var result = json(refund(provider, originalId, key, AMOUNT), 202);
    json(refund(provider, originalId, key, AMOUNT - 1), 409);
    UUID otherId = approve(provider, version);
    json(refund(provider, otherId, key, AMOUNT), 409);
    json(
        refund(provider, UUID.fromString(result.path("id").asText()), UUID.randomUUID(), AMOUNT),
        409);
    assertThat(count(key)).isEqualTo(1);
  }

  @Test
  @DisplayName("잘못된 수단·대역·시험 계정·금액·본문 타입을 거절하며 금융 기록을 만들지 않는다")
  void rejectsInvalidInputs() throws Exception {
    String version = normal(PaymentProvider.EASY_PAY);
    for (var change :
        List.of(
            Map.<String, Object>of("method", "CREDIT_CARD"),
            Map.<String, Object>of("accountRef", funding.getAccountRef()),
            Map.<String, Object>of("accountRef", "missing"),
            Map.<String, Object>of("fundingAccountRef", wallet.getAccountRef()),
            Map.<String, Object>of("amount", 0),
            Map.<String, Object>of("amount", 10_000_001),
            Map.<String, Object>of("amount", 1000.1),
            Map.<String, Object>of("amount", "1000"),
            Map.<String, Object>of("method", 0),
            Map.<String, Object>of("method", "0"),
            Map.<String, Object>of("method", "GIFT_CARD"),
            Map.<String, Object>of("accountRef", true),
            Map.<String, Object>of("extra", "unknown"))) {
      UUID key = UUID.randomUUID();
      var body = new HashMap<>(approval(key, PaymentProvider.EASY_PAY, version));
      body.putAll(change);
      json(post(PaymentProvider.EASY_PAY, "/payments", body), 400);
      assertThat(count(key)).isZero();
    }
    assertBalance(wallet, AMOUNT);
  }

  @Test
  @DisplayName("같은 키 동시 승인과 마지막 지갑 잔액 경쟁은 성공 차감을 한 번만 만든다")
  void serializesWalletApprovalCompetition() throws Exception {
    String version = normal(PaymentProvider.EASY_PAY);
    UUID same = UUID.randomUUID();
    var results =
        concurrent(
            () ->
                post(
                    PaymentProvider.EASY_PAY,
                    "/payments",
                    approval(same, PaymentProvider.EASY_PAY, version)),
            4);
    assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(202, 409));
    assertThat(count(same)).isEqualTo(1);
    assertBalance(wallet, 0);
    UUID insufficient = UUID.randomUUID();
    var declined =
        json(
            post(
                PaymentProvider.EASY_PAY,
                "/payments",
                approval(insufficient, PaymentProvider.EASY_PAY, version)),
            202);
    assertThat(declined.path("status").asText()).isEqualTo("DECLINED");
    assertThat(
            json(
                post(
                    PaymentProvider.EASY_PAY,
                    "/payments",
                    approval(insufficient, PaymentProvider.EASY_PAY, version)),
                202))
        .isEqualTo(declined);
  }

  @Test
  @DisplayName("서로 다른 승인 업무 키가 마지막 지갑 잔액을 경쟁해도 하나만 성공한다")
  void approvesOnlyOneDistinctWalletKey() throws Exception {
    String version = normal(PaymentProvider.EASY_PAY);
    var results =
        concurrent(
            () ->
                post(
                    PaymentProvider.EASY_PAY,
                    "/payments",
                    approval(UUID.randomUUID(), PaymentProvider.EASY_PAY, version)),
            2);
    List<String> statuses = new ArrayList<>();
    for (var result : results) statuses.add(json(result, 202).path("status").asText());
    assertThat(statuses).containsExactlyInAnyOrder("SUCCEEDED", "DECLINED");
    assertBalance(wallet, 0);
  }

  @Test
  @DisplayName("입금과 서로 다른 키의 동시 환불은 원승인 잠금으로 현금·지갑을 한 번만 반영한다")
  void serializesReceiptAndRefundCompetition() throws Exception {
    var provider = PaymentProvider.EASY_PAY;
    UUID id = approve(provider, scenario(provider, 0, 0, 0, 0, 0, 0, 0, 0));
    var results =
        concurrent(
            () -> {
              payments.receiveDue();
              return refund(provider, id, UUID.randomUUID(), AMOUNT);
            },
            2);
    assertThat(results.stream().map(r -> r.getStatusCode().value()))
        .containsExactlyInAnyOrder(202, 409);
    payments.receiveDue();
    assertBalance(funding, 1_000_000);
    assertBalance(wallet, AMOUNT);
    assertThat(cash().stream().mapToLong(Long::longValue).sum()).isZero();
    assertThat(cash().size()).isIn(0, 2);
  }

  @Test
  @DisplayName("잠긴 선행 원승인과 재원 대기가 있어도 다른 처리 가능한 입금은 계속한다")
  void skipsLockedReceiptAndContinuesAfterFundingContention() throws Exception {
    var provider = PaymentProvider.PG_A;
    String version = scenario(provider, 0, 0, 0, 0, 0, 0, 0, 0);
    UUID first = approve(provider, version);
    MockAccount secondFunding =
        accounts.save(
            MockAccount.open(
                "PAYOUT", "f-" + UUID.randomUUID(), AccountPurpose.PAYOUT, 1_000_000, NOW));
    var body = new HashMap<>(approval(UUID.randomUUID(), provider, version));
    body.put("fundingAccountRef", secondFunding.getAccountRef());
    UUID second = UUID.fromString(json(post(provider, "/payments", body), 202).path("id").asText());
    jdbc.update(
        "UPDATE finance_mock.mock_transactions SET pg_receipt_due_at = pg_receipt_due_at - interval '1 day' WHERE id=?",
        first);
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      lock(lock, first, "mock_transactions");
      payments.receiveDue();
      assertBalance(funding, 1_000_000);
      assertBalance(secondFunding, 1_030_000);
      lock.rollback();
    }
    MockAccount thirdFunding =
        accounts.save(
            MockAccount.open(
                "PAYOUT", "f-" + UUID.randomUUID(), AccountPurpose.PAYOUT, 1_000_000, NOW));
    body.put("businessKey", UUID.randomUUID());
    body.put("fundingAccountRef", thirdFunding.getAccountRef());
    json(post(provider, "/payments", body), 202);
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      lock(lock, funding.getId(), "mock_accounts");
      payments.receiveDue();
      assertBalance(funding, 1_000_000);
      assertBalance(thirdFunding, 1_030_000);
      lock.rollback();
    }
    payments.receiveDue();
    assertBalance(funding, 1_030_000);
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_received_amount FROM finance_mock.mock_transactions WHERE id=?",
                Long.class,
                second))
        .isEqualTo(AMOUNT);
  }

  @Test
  @DisplayName("입금 후 환불 저장 실패는 현금·지갑·멱등·환불을 롤백하고 원키 재시도로 완료한다")
  void rollsBackFinancialChangesOnStorageFailure() throws Exception {
    var provider = PaymentProvider.EASY_PAY;
    UUID id = approve(provider, scenario(provider, 0, 0, 0, 0, 0, 0, 0, 0));
    payments.receiveDue();
    UUID key = UUID.randomUUID();
    doThrow(new DataAccessResourceFailureException("injected"))
        .doCallRealMethod()
        .when(cashEntries)
        .save(any());
    json(refund(provider, id, key, AMOUNT), 500);
    assertThat(count(key)).isZero();
    assertBalance(funding, 1_030_000);
    assertBalance(wallet, 0);
    assertThat(cash()).containsExactly(30_000L);
    json(refund(provider, id, key, AMOUNT), 202);
    assertBalance(funding, 1_000_000);
    assertBalance(wallet, AMOUNT);
  }

  @Test
  @DisplayName("환불 최종 저장 실패는 입금 상쇄·지갑 복원·멱등 기록도 함께 롤백한다")
  void rollsBackWalletAndReceiptCancellation() throws Exception {
    var provider = PaymentProvider.EASY_PAY;
    String version = normal(provider);
    UUID originalId = approve(provider, version);
    UUID key = UUID.randomUUID();
    doThrow(new DataAccessResourceFailureException("injected"))
        .doCallRealMethod()
        .when(transactions)
        .save(any());

    json(refund(provider, originalId, key, AMOUNT), 500);

    assertBalance(wallet, 0);
    assertBalance(funding, 1_000_000);
    assertThat(count(key)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_received_at IS NULL FROM finance_mock.mock_transactions WHERE id=?",
                Boolean.class,
                originalId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.idempotency_records r WHERE NOT EXISTS (SELECT 1 FROM finance_mock.mock_transactions t WHERE t.id=r.resource_id)",
                Long.class))
        .isZero();
    json(refund(provider, originalId, key, AMOUNT), 202);
    assertBalance(wallet, AMOUNT);
  }

  @Test
  @DisplayName("입금 후 재원 잠금 대기가 상한을 넘으면 환불·현금 차감·지갑 복원을 남기지 않는다")
  void boundsFundingLockWait() throws Exception {
    var provider = PaymentProvider.EASY_PAY;
    UUID originalId = approve(provider, scenario(provider, 0, 0, 0, 0, 0, 0, 0, 0));
    payments.receiveDue();
    UUID key = UUID.randomUUID();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      lock(lock, funding.getId(), "mock_accounts");
      json(concurrent(() -> refund(provider, originalId, key, AMOUNT), 1).getFirst(), 503);
      lock.rollback();
    }
    assertThat(count(key)).isZero();
    assertBalance(wallet, 0);
    assertBalance(funding, 1_030_000);
    assertThat(cash()).containsExactly(30_000L);
  }

  @Test
  @DisplayName("다른 트랜잭션의 원승인·지갑 잠금은 1초 상한에서 실패하고 변경을 롤백한다")
  void boundsLockWaitAndRollsBack() throws Exception {
    var provider = PaymentProvider.EASY_PAY;
    String version = normal(provider);
    UUID key = UUID.randomUUID();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      lock(lock, wallet.getId(), "mock_accounts");
      var result =
          concurrent(() -> post(provider, "/payments", approval(key, provider, version)), 1)
              .getFirst();
      json(result, 503);
      lock.rollback();
    }
    assertThat(count(key)).isZero();
    UUID originalId =
        UUID.fromString(
            json(post(provider, "/payments", approval(key, provider, version)), 202)
                .path("id")
                .asText());
    UUID refundKey = UUID.randomUUID();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      lock(lock, originalId, "mock_transactions");
      json(concurrent(() -> refund(provider, originalId, refundKey, AMOUNT), 1).getFirst(), 503);
      lock.rollback();
    }
    assertThat(count(refundKey)).isZero();
    assertBalance(wallet, 0);
    json(refund(provider, originalId, refundKey, AMOUNT), 202);
    assertBalance(wallet, AMOUNT);
  }

  @Test
  @DisplayName("승인·환불 응답과 조회의 지연 중 DB 트랜잭션·커넥션을 점유하지 않는다")
  void releasesDatabaseBeforeCommunicationDelays() throws Exception {
    var provider = PaymentProvider.PG_C;
    String version = scenario(provider, 0, 0, 0, 0, 0, 3000, 3000, 5);
    UUID key = UUID.randomUUID();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var approval =
          executor.submit(() -> post(provider, "/payments", approval(key, provider, version)));
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(count(key)).isEqualTo(1));
      assertNoConnectionDuring(approval);
      UUID originalId =
          UUID.fromString(json(approval.get(5, TimeUnit.SECONDS), 202).path("id").asText());
      UUID refundKey = UUID.randomUUID();
      var refund = executor.submit(() -> refund(provider, originalId, refundKey, AMOUNT));
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(count(refundKey)).isEqualTo(1));
      assertNoConnectionDuring(refund);
      json(refund.get(5, TimeUnit.SECONDS), 202);
      var lookup = executor.submit(() -> lookup(provider, key, "APPROVE", version));
      assertNoConnectionDuring(lookup);
      json(lookup.get(5, TimeUnit.SECONDS), 200);
    }
  }

  @Test
  @DisplayName("PROCESSING fixture 조회는 확정이나 재실행 없이 저장 상태를 보존한다")
  void preservesProcessingFixture() throws Exception {
    UUID key = UUID.randomUUID();
    String version = normal(PaymentProvider.PG_A);
    transactions.save(
        MockTransaction.approve(
            PaymentProvider.PG_A,
            key,
            MockPaymentMethod.CREDIT_CARD,
            null,
            funding.getId(),
            AMOUNT,
            version,
            1L,
            NOW));
    assertThat(
            json(lookup(PaymentProvider.PG_A, key, "APPROVE", version), 200)
                .path("status")
                .asText())
        .isEqualTo("PROCESSING");
    assertThat(count(key)).isEqualTo(1);
    assertThat(cash()).isEmpty();
  }

  private void assertNoConnectionDuring(java.util.concurrent.Future<?> response) {
    await()
        .during(Duration.ofMillis(150))
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              assertThat(response.isDone()).isFalse();
              assertThat(
                      dataSource
                          .unwrap(HikariDataSource.class)
                          .getHikariPoolMXBean()
                          .getActiveConnections())
                  .isZero();
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'",
                Long.class))
        .isZero();
  }

  private static void lock(Connection connection, UUID id, String table) throws Exception {
    try (var statement =
        connection.prepareStatement(
            "SELECT id FROM finance_mock." + table + " WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      statement.executeQuery().close();
    }
  }

  private List<ResponseEntity<String>> concurrent(Callable<ResponseEntity<String>> call, int count)
      throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(count)) {
      var futures = new ArrayList<java.util.concurrent.Future<ResponseEntity<String>>>();
      for (int i = 0; i < count; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return call.call();
                }));
      start.countDown();
      var results = new ArrayList<ResponseEntity<String>>();
      for (var future : futures) results.add(future.get(4, TimeUnit.SECONDS));
      return results;
    }
  }

  private Map<String, Object> approval(UUID key, PaymentProvider provider, String version) {
    return Map.of(
        "businessKey",
        key,
        "method",
        provider == PaymentProvider.EASY_PAY ? "EASY_PAY" : "CREDIT_CARD",
        "accountRef",
        wallet.getAccountRef(),
        "fundingAccountRef",
        funding.getAccountRef(),
        "amount",
        AMOUNT,
        "scenarioVersion",
        version);
  }

  private UUID approve(PaymentProvider provider, String version) throws Exception {
    return UUID.fromString(
        json(post(provider, "/payments", approval(UUID.randomUUID(), provider, version)), 202)
            .path("id")
            .asText());
  }

  private ResponseEntity<String> post(PaymentProvider provider, String path, Object body) {
    return post(provider, path, body, PAYMENT);
  }

  private ResponseEntity<String> post(
      PaymentProvider provider, String path, Object body, String credential) {
    var headers = new HttpHeaders();
    if (credential != null) headers.setBearerAuth(credential);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    return http.exchange(
        "/mock/providers/" + provider + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  private ResponseEntity<String> refund(PaymentProvider provider, UUID id, UUID key, long amount) {
    return post(
        provider, "/payments/" + id + "/refunds", Map.of("businessKey", key, "amount", amount));
  }

  private ResponseEntity<String> lookup(
      PaymentProvider provider, UUID key, String operation, String version) {
    return get(
        "/mock/providers/"
            + provider
            + "/transactions/"
            + key
            + "?operation="
            + operation
            + "&scenarioVersion="
            + version,
        PAYMENT);
  }

  private ResponseEntity<String> get(String path, String credential) {
    var headers = new HttpHeaders();
    if (credential != null) headers.setBearerAuth(credential);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private JsonNode json(ResponseEntity<String> response, int expected) throws Exception {
    assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(expected);
    return mapper.readTree(response.getBody());
  }

  private String normal(PaymentProvider provider) {
    return scenario(provider, 0, 0, 0, 0, 0, 0, 0, 5);
  }

  private String scenario(
      PaymentProvider provider,
      int reject,
      int loss,
      int refundReject,
      int refundLoss,
      int lookupFailure,
      int delay,
      int lookupDelay,
      int days) {
    String version = "t-" + UUID.randomUUID().toString().substring(0, 18);
    scenarios.save(
        MockScenario.payment(
            provider,
            version,
            new MockScenario.Delay(delay, delay),
            BigDecimal.valueOf(reject),
            BigDecimal.valueOf(loss),
            new MockScenario.Delay(delay, delay),
            BigDecimal.valueOf(refundReject),
            BigDecimal.valueOf(refundLoss),
            new MockScenario.Delay(lookupDelay, lookupDelay),
            BigDecimal.valueOf(lookupFailure),
            days,
            42L,
            NOW));
    return version;
  }

  private long count(UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM finance_mock.mock_transactions WHERE business_key=?",
        Long.class,
        key);
  }

  private List<Long> cash() {
    return jdbc.queryForList(
        "SELECT amount FROM finance_mock.mock_cash_entries WHERE account_id=?",
        Long.class,
        funding.getId());
  }

  private void assertBalance(MockAccount account, long balance) {
    assertThat(
            jdbc.queryForMap(
                "SELECT balance,reserved_amount FROM finance_mock.mock_accounts WHERE id=?",
                account.getId()))
        .containsEntry("balance", balance)
        .containsEntry("reserved_amount", 0L);
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
