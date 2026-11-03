package com.commercesettle.financemock.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.commercesettle.financemock.FinanceMockApplication;
import com.commercesettle.financemock.application.port.IdempotencyStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 전용 PostgreSQL과 실제 HTTP를 사용하고, 테스트 트랜잭션으로 커밋·롤백을 가리지 않는다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "finance-mock.pg.receipts-enabled=false",
      "app.internal-auth.senders[operator\\:test].current="
          + MockPreparationIntegrationTest.OPERATOR,
      "app.internal-auth.senders[operator\\:other].current=" + MockPreparationIntegrationTest.OTHER,
      "app.internal-auth.senders[service\\:settlement].current="
          + MockPreparationIntegrationTest.SETTLEMENT,
      "app.internal-auth.senders[service\\:payment].current="
          + MockPreparationIntegrationTest.PAYMENT,
      "app.internal-auth.senders[gateway].current=" + MockPreparationIntegrationTest.GATEWAY
    })
@Testcontainers
@Timeout(45)
class MockPreparationIntegrationTest {
  static final String OPERATOR = "test-operator-credential-0123456789abcdef012345";
  static final String OTHER = "other-operator-credential-0123456789abcdef01234";
  static final String SETTLEMENT = "test-settlement-credential-0123456789abcdef0123";
  static final String PAYMENT = "test-payment-credential-0123456789abcdef0123456";
  static final String GATEWAY = "test-gateway-credential-0123456789abcdef0123456";
  private static final String MAX_CALLER = "operator:" + "a".repeat(91);
  private static final String LONG_CALLER = "operator:" + "a".repeat(92);
  private static final String OTHER_LONG_CALLER = "operator:" + "b".repeat(92);
  private static final Map<String, String> BOUNDARY_CREDENTIALS =
      Map.of(
          MAX_CALLER, "boundary-operator-credential-0123456789abcdef01",
          LONG_CALLER, "long-operator-credential-0123456789abcdef0123456",
          OTHER_LONG_CALLER, "other-long-operator-credential-0123456789abcdef0");

  @DynamicPropertySource
  static void registerBoundaryOperators(DynamicPropertyRegistry properties) {
    BOUNDARY_CREDENTIALS.forEach(
        (caller, credential) ->
            properties.add("app.internal-auth.senders[" + caller + "].current", () -> credential));
  }

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @MockitoSpyBean IdempotencyStore idempotency;

  @ParameterizedTest
  @ValueSource(strings = {"PG_A", "PG_B", "PG_C", "EASY_PAY", "PAYOUT"})
  @DisplayName("대역별 시험 계정·불변 시나리오를 생성하고 원키 재요청은 원결과를 반환한다")
  void createsAndReplaysPreparation(String provider) throws Exception {
    String key = key();
    var body = account(provider);
    var response = post("/mock/accounts", key, body);
    var created = json(response, 201);
    assertThat(response.getHeaders().getLocation().toString())
        .isEqualTo("/mock/accounts/" + created.path("id").asText());
    assertThat(json(get(response.getHeaders().getLocation().toString(), OPERATOR), 200))
        .isEqualTo(created);
    assertThat(json(post("/mock/accounts", key, body), 201)).isEqualTo(created);

    var settings = scenario(provider);
    var scenarioResponse = post("/mock/scenarios", key, settings);
    var configured = json(scenarioResponse, 201);
    assertThat(scenarioResponse.getHeaders().getLocation().toString())
        .isEqualTo("/mock/scenarios/" + configured.path("id").asText());
    assertThat(configured.path("createdAt").asText()).endsWith("+09:00");
    assertThat(json(post("/mock/scenarios", key, settings), 201)).isEqualTo(configured);
    assertThat(count("mock_scenarios", "id", UUID.fromString(configured.path("id").asText())))
        .isEqualTo(1);
    json(get("/mock/accounts/" + UUID.randomUUID(), OPERATOR), 404);
  }

  @Test
  @DisplayName("잔액이 변해도 생성 재요청은 원결과이고 GET은 현재 예약·잔액·버전을 반환한다")
  void keepsOriginalCreationResultAfterFinancialChanges() throws Exception {
    String key = key();
    var body = account("PAYOUT");
    var original = json(post("/mock/accounts", key, body), 201);
    UUID id = UUID.fromString(original.path("id").asText());
    jdbc.update(
        "UPDATE finance_mock.mock_accounts SET balance=900, reserved_amount=100, version=1 WHERE id=?",
        id);

    assertThat(json(post("/mock/accounts", key, body), 201)).isEqualTo(original);
    var current = json(get("/mock/accounts/" + id, OPERATOR), 200);
    assertThat(current.path("openingBalance").asLong()).isEqualTo(1000);
    assertThat(current.path("balance").asLong()).isEqualTo(900);
    assertThat(current.path("reservedAmount").asLong()).isEqualTo(100);
    assertThat(current.path("version").asLong()).isEqualTo(1);
    body.put("balance", 2000);
    json(post("/mock/accounts", key, body), 409);
    json(post("/mock/accounts", key(), body), 409);
    assertThat(json(get("/mock/accounts/" + id, OPERATOR), 200)).isEqualTo(current);
  }

  @Test
  @DisplayName("운영자별 키를 분리하고 다른 본문·기존 시나리오 버전은 덮어쓰지 않는다")
  void scopesKeysAndPreservesVersions() throws Exception {
    String key = key();
    var first = scenario("PG_A");
    var original = json(post("/mock/scenarios", key, first), 201);
    first.put("rejectionRate", 1);
    json(post("/mock/scenarios", key, first), 409);
    json(post("/mock/scenarios", key(), first), 409);
    first.put("rejectionRate", new BigDecimal("0.0000000"));
    assertThat(json(post("/mock/scenarios", key, first), 201)).isEqualTo(original);
    var other = scenario("PAYOUT");
    json(post("/mock/scenarios", key, other, OTHER, false), 201);
    assertThat(
            jdbc.queryForObject(
                "SELECT rejection_rate FROM finance_mock.mock_scenarios WHERE id=?",
                BigDecimal.class,
                UUID.fromString(original.path("id").asText())))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.idempotency_records WHERE key_hash=? AND operation='MOCK:SCENARIO:CREATE'",
                Integer.class,
                sha(key)))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "SELECT actor_id FROM finance_mock.idempotency_records WHERE key_hash=? AND operation='MOCK:SCENARIO:CREATE' ORDER BY actor_id",
                String.class,
                sha(key)))
        .containsExactly("operator:other", "operator:test");
  }

  @ParameterizedTest
  @ValueSource(strings = {"accounts", "scenarios"})
  @DisplayName("100·101자 운영자 ID도 저장 한도를 지키며 같은 키를 운영자별로 구분하고 재요청을 복원한다")
  void preservesOperatorScopesAtActorStorageBoundary(String resource) throws Exception {
    String key = key();
    String path = "/mock/" + resource;
    var ids = new ArrayList<String>();
    for (String caller : List.of(MAX_CALLER, LONG_CALLER, OTHER_LONG_CALLER)) {
      var body = "accounts".equals(resource) ? account("PAYOUT") : scenario("PAYOUT");
      String credential = BOUNDARY_CREDENTIALS.get(caller);
      var original = json(post(path, key, body, credential, false), 201);
      ids.add(original.path("id").asText());
      assertThat(json(post(path, key, body, credential, false), 201)).isEqualTo(original);
      body.put("accounts".equals(resource) ? "balance" : "seed", 2000);
      json(post(path, key, body, credential, false), 409);
    }

    assertThat(ids).doesNotHaveDuplicates();
    var actors =
        jdbc.queryForList(
            "SELECT actor_id FROM finance_mock.idempotency_records WHERE actor_kind='OPERATOR' AND key_hash=?",
            String.class,
            sha(key));
    assertThat(actors).hasSize(3).doesNotHaveDuplicates().contains(MAX_CALLER);
    assertThat(actors).allSatisfy(actor -> assertThat(actor.length()).isLessThanOrEqualTo(100));
    assertThat(actors.stream().filter(actor -> actor.startsWith("sha256:")).toList()).hasSize(2);
  }

  @Test
  @DisplayName("서비스·Gateway·미인증·위조 신원 요청은 준비 데이터나 멱등 기록을 남기지 않는다")
  void rejectsUnauthorizedSources() throws Exception {
    int accountsBefore = tableCount("mock_accounts");
    int scenariosBefore = tableCount("mock_scenarios");
    int keysBefore = tableCount("idempotency_records");
    for (String credential : new String[] {null, "invalid", PAYMENT, SETTLEMENT, GATEWAY}) {
      int expected = credential == null || "invalid".equals(credential) ? 401 : 403;
      json(post("/mock/accounts", key(), account("EASY_PAY"), credential, false), expected);
      json(post("/mock/scenarios", key(), scenario("PG_A"), credential, false), expected);
      json(get("/mock/accounts/" + UUID.randomUUID(), credential), expected);
    }
    json(post("/mock/accounts", key(), account("PAYOUT"), OPERATOR, true), 403);
    json(post("/mock/scenarios", key(), scenario("PG_A"), OPERATOR, true), 403);
    assertThat(tableCount("mock_accounts")).isEqualTo(accountsBefore);
    assertThat(tableCount("mock_scenarios")).isEqualTo(scenariosBefore);
    assertThat(tableCount("idempotency_records")).isEqualTo(keysBefore);
  }

  @Test
  @DisplayName("계정 필수값·범위·정수·알 수 없는 필드와 경로·키 형식을 거절한다")
  void validatesAccountRequests() throws Exception {
    var valid = account("EASY_PAY");
    for (var invalid :
        List.of(
            Map.of("providerId", "UNKNOWN"),
            Map.of("accountRef", " "),
            Map.of("accountRef", "x".repeat(101)),
            Map.of("purpose", "UNKNOWN"),
            Map.of("balance", -1),
            Map.of("balance", 0.1),
            Map.of("balance", "100"),
            Map.of("extra", 1))) {
      var body = new HashMap<>(valid);
      body.putAll(invalid);
      var error = post("/mock/accounts", key(), body);
      assertThat(error.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
      assertThat(json(error, 400).path("code").asText()).isEqualTo("COMMON_VALIDATION_FAILED");
    }
    for (String field : valid.keySet()) {
      var body = new HashMap<>(valid);
      body.remove(field);
      json(post("/mock/accounts", key(), body), 400);
    }
    json(post("/mock/accounts", "short", valid), 400);
    json(post("/mock/accounts", "x".repeat(129), valid), 400);
    json(post("/mock/accounts", null, valid), 400);
    json(get("/mock/accounts/not-a-uuid", OPERATOR), 400);
    assertThat(count("mock_accounts", "account_ref", valid.get("accountRef"))).isZero();
  }

  @Test
  @DisplayName("시나리오의 작업별 지연·확률·입금 기한과 PAYOUT의 null 필드를 검증한다")
  void validatesScenarioRequestsWithoutRounding() throws Exception {
    var valid = scenario("PG_A");
    for (var invalid :
        List.of(
            Map.of("providerId", "UNKNOWN"),
            Map.of("scenarioVersion", " "),
            Map.of("scenarioVersion", "x".repeat(33)),
            Map.of("minDelayMs", -1),
            Map.of("minDelayMs", 1),
            Map.of("refundMinDelayMs", 1),
            Map.of("lookupMinDelayMs", 1),
            Map.of("rejectionRate", new BigDecimal("0.00000001")),
            Map.of("responseLossRate", 1.1),
            Map.of("refundRejectionRate", -0.1),
            Map.of("refundResponseLossRate", 2),
            Map.of("lookupFailureRate", -1),
            Map.of("settlementDelayBusinessDays", 366),
            Map.of("minDelayMs", 0.1),
            Map.of("seed", "1"),
            Map.of("unknown", 1))) {
      var body = new HashMap<>(valid);
      body.putAll(invalid);
      json(post("/mock/scenarios", key(), body), 400);
    }
    for (String field : valid.keySet()) {
      var body = new HashMap<>(valid);
      body.remove(field);
      json(post("/mock/scenarios", key(), body), 400);
    }
    for (String field :
        List.of(
            "settlementDelayBusinessDays",
            "refundMinDelayMs",
            "refundMaxDelayMs",
            "refundRejectionRate",
            "refundResponseLossRate")) {
      var payment = new HashMap<>(valid);
      payment.put(field, null);
      assertFieldError(json(post("/mock/scenarios", key(), payment), 400), field);
      var payout = scenario("PAYOUT");
      payout.put(field, 0);
      assertFieldError(json(post("/mock/scenarios", key(), payout), 400), field);
    }
    for (var range :
        Map.of(
                "minDelayMs",
                "maxDelayMs",
                "refundMinDelayMs",
                "refundMaxDelayMs",
                "lookupMinDelayMs",
                "lookupMaxDelayMs")
            .entrySet()) {
      var body = new HashMap<>(valid);
      body.put(range.getKey(), 1);
      assertFieldError(json(post("/mock/scenarios", key(), body), 400), range.getValue());
    }
    assertThat(count("mock_scenarios", "scenario_version", valid.get("scenarioVersion"))).isZero();
    valid.put("rejectionRate", new BigDecimal("0.1234567"));
    var created = json(post("/mock/scenarios", key(), valid), 201);
    assertThat(
            jdbc.queryForObject(
                "SELECT rejection_rate FROM finance_mock.mock_scenarios WHERE id=?",
                BigDecimal.class,
                UUID.fromString(created.path("id").asText())))
        .isEqualByComparingTo("0.1234567");
  }

  @ParameterizedTest
  @ValueSource(strings = {"accounts", "scenarios"})
  @DisplayName("동시 같은 키 요청은 원결과 하나로 수렴하고 새 키의 중복 자원은 충돌한다")
  void handlesConcurrentRequests(String resource) throws Exception {
    var body = "accounts".equals(resource) ? account("PAYOUT") : scenario("PAYOUT");
    String path = "/mock/" + resource;
    String key = key();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<java.util.concurrent.Future<ResponseEntity<String>>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return post(path, key, body);
                }));
      start.countDown();
      var first = json(futures.getFirst().get(10, TimeUnit.SECONDS), 201);
      for (var future : futures)
        assertThat(json(future.get(10, TimeUnit.SECONDS), 201)).isEqualTo(first);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.idempotency_records WHERE key_hash=?",
                Integer.class,
                sha(key)))
        .isEqualTo(1);
    json(post(path, key(), body), 409);
    assertThat(
            count(
                "mock_" + resource,
                "accounts".equals(resource) ? "account_ref" : "scenario_version",
                body.get("accounts".equals(resource) ? "accountRef" : "scenarioVersion")))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"accounts", "scenarios"})
  @DisplayName("완료 키 저장 전 중단은 자원도 롤백하고 같은 키 재시도로 정상 생성한다")
  void rollsBackAndResumesPreparation(String resource) throws Exception {
    var body = "accounts".equals(resource) ? account("EASY_PAY") : scenario("PG_B");
    String field = "accounts".equals(resource) ? "account_ref" : "scenario_version";
    Object value = body.get("accounts".equals(resource) ? "accountRef" : "scenarioVersion");
    String key = key();
    doThrow(new DataAccessResourceFailureException("injected preparation interruption"))
        .doCallRealMethod()
        .when(idempotency)
        .saveCompleted(any(), any(), anyString(), any(), anyInt(), any());

    json(post("/mock/" + resource, key, body), 500);
    assertThat(count("mock_" + resource, field, value)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.idempotency_records WHERE key_hash=?",
                Integer.class,
                sha(key)))
        .isZero();
    var original = json(post("/mock/" + resource, key, body), 201);
    assertThat(json(post("/mock/" + resource, key, body), 201)).isEqualTo(original);
  }

  @Test
  @DisplayName("다른 트랜잭션의 계정 유니크 키 잠금은 유한하게 거절하고 롤백 후 재개한다")
  void boundsUniqueKeyLockWait() throws Exception {
    var body = account("PAYOUT");
    String key = key();
    try (Connection locked = dataSource.getConnection()) {
      locked.setAutoCommit(false);
      try (var insert =
          locked.prepareStatement(
              "INSERT INTO finance_mock.mock_accounts(id,provider_id,account_ref,purpose,opening_balance,balance,reserved_amount,created_at,updated_at,version) VALUES (?,'PAYOUT',?,'PAYOUT',1000,1000,0,now(),now(),0)")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setString(2, body.get("accountRef").toString());
        insert.executeUpdate();
      }
      long started = System.nanoTime();
      json(post("/mock/accounts", key, body), 409);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
      locked.rollback();
    }
    assertThat(count("mock_accounts", "account_ref", body.get("accountRef"))).isZero();
    json(post("/mock/accounts", key, body), 201);
  }

  @Test
  @DisplayName("새 앱 인스턴스도 DB의 원생성 결과를 재사용하고 금융 버전·금액을 바꾸지 않는다")
  void replaysFromDatabaseAfterNewApplicationStart() throws Exception {
    var account = account("PAYOUT");
    var scenario = scenario("PAYOUT");
    String accountKey = key();
    String scenarioKey = key();
    var originalAccount = json(post("/mock/accounts", accountKey, account), 201);
    var originalScenario = json(post("/mock/scenarios", scenarioKey, scenario), 201);

    try (var restarted =
        new SpringApplicationBuilder(FinanceMockApplication.class)
            .run(
                "--server.port=0",
                "--management.server.port=0",
                "--management.tracing.enabled=false",
                "--finance-mock.pg.receipts-enabled=false",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--app.internal-auth.senders[service:settlement].current=" + SETTLEMENT,
                "--app.internal-auth.senders[service:payment].current=" + PAYMENT,
                "--app.internal-auth.senders[operator:test].current=" + OPERATOR)) {
      int port = ((ServletWebServerApplicationContext) restarted).getWebServer().getPort();
      assertThat(
              json(post("http://localhost:" + port + "/mock/accounts", accountKey, account), 201))
          .isEqualTo(originalAccount);
      assertThat(
              json(
                  post("http://localhost:" + port + "/mock/scenarios", scenarioKey, scenario), 201))
          .isEqualTo(originalScenario);
    }
  }

  @Test
  @DisplayName("준비한 지갑·재원·시나리오를 기존 승인·환불·지급에서 쓰고 새 버전은 원거래를 바꾸지 않는다")
  void usesPreparedDataWithoutChangingExistingFinancialVersions() throws Exception {
    var wallet = account("EASY_PAY");
    var funding = account("PAYOUT");
    var paymentScenario = scenario("EASY_PAY");
    var payoutScenario = scenario("PAYOUT");
    var walletView = json(post("/mock/accounts", key(), wallet), 201);
    var fundingView = json(post("/mock/accounts", key(), funding), 201);
    json(post("/mock/scenarios", key(), paymentScenario), 201);
    json(post("/mock/scenarios", key(), payoutScenario), 201);
    UUID approvalKey = UUID.randomUUID();
    var approval =
        Map.of(
            "businessKey",
            approvalKey,
            "method",
            "EASY_PAY",
            "accountRef",
            wallet.get("accountRef"),
            "fundingAccountRef",
            funding.get("accountRef"),
            "amount",
            100,
            "scenarioVersion",
            paymentScenario.get("scenarioVersion"));
    var approved =
        json(post("/mock/providers/EASY_PAY/payments", key(), approval, PAYMENT, false), 202);
    UUID payoutKey = UUID.randomUUID();
    var payout =
        Map.of(
            "businessKey",
            payoutKey,
            "accountRef",
            funding.get("accountRef"),
            "sellerAccountRef",
            "synthetic-seller-account",
            "amount",
            100,
            "scenarioVersion",
            payoutScenario.get("scenarioVersion"));
    var paid = json(post("/mock/payouts", key(), payout, SETTLEMENT, false), 202);

    var changed = new HashMap<>(paymentScenario);
    changed.put("refundRejectionRate", 1);
    json(post("/mock/scenarios", key(), changed), 409);
    changed.put("scenarioVersion", "new-" + UUID.randomUUID().toString().substring(0, 20));
    json(post("/mock/scenarios", key(), changed), 201);
    var changedPayout = new HashMap<>(payoutScenario);
    changedPayout.put("scenarioVersion", "new-" + UUID.randomUUID().toString().substring(0, 20));
    changedPayout.put("rejectionRate", 1);
    json(post("/mock/scenarios", key(), changedPayout), 201);

    var refunded =
        json(
            post(
                "/mock/providers/EASY_PAY/payments/" + approved.path("id").asText() + "/refunds",
                key(),
                Map.of("businessKey", UUID.randomUUID(), "amount", 100),
                PAYMENT,
                false),
            202);
    assertThat(refunded.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(refunded.path("scenarioVersion").asText())
        .isEqualTo(paymentScenario.get("scenarioVersion"));
    assertThat(json(post("/mock/payouts", key(), payout, SETTLEMENT, false), 202)).isEqualTo(paid);
    assertThat(
            jdbc.queryForMap(
                "SELECT account_id, funding_account_id, scenario_version FROM finance_mock.mock_transactions WHERE id=?",
                UUID.fromString(approved.path("id").asText())))
        .containsEntry("account_id", UUID.fromString(walletView.path("id").asText()))
        .containsEntry("funding_account_id", UUID.fromString(fundingView.path("id").asText()))
        .containsEntry("scenario_version", paymentScenario.get("scenarioVersion"));
    assertThat(
            json(get("/mock/accounts/" + walletView.path("id").asText(), OPERATOR), 200)
                .path("balance")
                .asLong())
        .isEqualTo(1000);
    assertThat(
            json(get("/mock/accounts/" + fundingView.path("id").asText(), OPERATOR), 200)
                .path("balance")
                .asLong())
        .isEqualTo(900);
  }

  private Map<String, Object> account(String provider) {
    return new HashMap<>(
        Map.of(
            "providerId",
            provider,
            "accountRef",
            "test-" + UUID.randomUUID(),
            "purpose",
            "EASY_PAY".equals(provider) ? "WALLET" : "PAYOUT",
            "balance",
            1000));
  }

  private Map<String, Object> scenario(String provider) {
    var body = new HashMap<String, Object>();
    body.put("providerId", provider);
    body.put("scenarioVersion", "t-" + UUID.randomUUID().toString().substring(0, 20));
    for (String field :
        List.of(
            "minDelayMs",
            "maxDelayMs",
            "rejectionRate",
            "responseLossRate",
            "lookupFailureRate",
            "lookupMinDelayMs",
            "lookupMaxDelayMs")) body.put(field, 0);
    body.put("seed", 20261103L);
    for (String field :
        List.of(
            "settlementDelayBusinessDays",
            "refundMinDelayMs",
            "refundMaxDelayMs",
            "refundRejectionRate",
            "refundResponseLossRate")) body.put(field, "PAYOUT".equals(provider) ? null : 0);
    return body;
  }

  private ResponseEntity<String> post(String path, String key, Object body) {
    return post(path, key, body, OPERATOR, false);
  }

  private ResponseEntity<String> post(
      String path, String key, Object body, String credential, boolean forgedIdentity) {
    var headers = headers(credential);
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) headers.set("Idempotency-Key", key);
    if (forgedIdentity) headers.set("X-Authenticated-Account-Id", UUID.randomUUID().toString());
    return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<String> get(String path, String credential) {
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(credential)), String.class);
  }

  private HttpHeaders headers(String credential) {
    var headers = new HttpHeaders();
    if (credential != null) headers.setBearerAuth(credential);
    return headers;
  }

  private JsonNode json(ResponseEntity<String> response, int status) throws Exception {
    assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
    return mapper.readTree(response.getBody());
  }

  private void assertFieldError(JsonNode problem, String field) {
    assertThat(problem.path("errors").findValuesAsText("field")).contains(field);
    assertThat(problem.path("errors").findValuesAsText("reason"))
        .allSatisfy(reason -> assertThat(reason).isNotBlank());
  }

  private int count(String table, String column, Object value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM finance_mock." + table + " WHERE " + column + "=?",
        Integer.class,
        value);
  }

  private int tableCount(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM finance_mock." + table, Integer.class);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static String sha(String value) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
}
