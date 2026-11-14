package com.commercesettle.financemock.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.dev-fixtures.enabled=true",
      "app.internal-auth.senders[service\\:settlement].current="
          + DevFixtureIntegrationTest.SETTLEMENT,
      "app.internal-auth.senders[service\\:payment].current="
          + "test-payment-credential-0123456789abcdef0123456"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DevFixtureIntegrationTest {
  static final String SETTLEMENT = "test-settlement-credential-0123456789abcdef0123";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  @Autowired DevFixtureLoader loader;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;

  @Test
  void createsPlannedPgInputsWithoutOverwritingExistingProfiles() {
    var expected =
        Map.of(
            "PG_A", java.util.List.of("80", "150", "0.005"),
            "PG_B", java.util.List.of("300", "700", "0.03"),
            "PG_C", java.util.List.of("800", "1500", "0.08"),
            "EASY_PAY", java.util.List.of("100", "300", "0.01"));
    for (var entry : expected.entrySet()) {
      var row =
          jdbc.queryForMap(
              "SELECT * FROM finance_mock.mock_scenarios WHERE provider_id=? AND scenario_version='fixture-pg-planned-v1'",
              entry.getKey());
      assertThat(row.get("min_delay_ms")).isEqualTo(Integer.valueOf(entry.getValue().get(0)));
      assertThat(row.get("max_delay_ms")).isEqualTo(Integer.valueOf(entry.getValue().get(1)));
      assertThat((java.math.BigDecimal) row.get("rejection_rate"))
          .isEqualByComparingTo(entry.getValue().get(2));
      assertThat(row.get("refund_min_delay_ms")).isEqualTo(row.get("min_delay_ms"));
      assertThat(row.get("refund_max_delay_ms")).isEqualTo(row.get("max_delay_ms"));
      assertThat(row.get("refund_rejection_rate")).isEqualTo(row.get("rejection_rate"));
      assertThat((java.math.BigDecimal) row.get("response_loss_rate")).isZero();
      assertThat((java.math.BigDecimal) row.get("lookup_failure_rate")).isZero();
    }
    var before = jdbc.queryForList("SELECT * FROM finance_mock.mock_scenarios ORDER BY id");
    loader.load();
    assertThat(jdbc.queryForList("SELECT * FROM finance_mock.mock_scenarios ORDER BY id"))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT max_delay_ms FROM finance_mock.mock_scenarios WHERE provider_id='PG_A' AND scenario_version='fixture-pg-normal-v1'",
                Integer.class))
        .isZero();
  }

  @Test
  void createsActualPayoutRecordsAndPreservesBalanceAcrossResume() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(SETTLEMENT);
    headers.set("Idempotency-Key", "fixture-payout-loss");
    var body =
        Map.of(
            "businessKey",
            DevFixtureLoader.key(2),
            "accountRef",
            DevFixtureLoader.ACCOUNT,
            "sellerAccountRef",
            "mock-seller-00000000-0000-4000-8000-000000000002",
            "amount",
            19_550,
            "scenarioVersion",
            "fixture-response-loss-v1");
    // 유실 응답 이전에 금융 효과는 확정되며, 나머지 시나리오만 이어서 처리한다.
    assertThat(
            http.postForEntity("/mock/payouts", new HttpEntity<>(body, headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(504);
    loader.runScenarios();
    assertThat(
            http.exchange(
                    "/mock/payouts/"
                        + DevFixtureLoader.key(2)
                        + "?scenarioVersion=fixture-response-loss-v1",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            http.exchange(
                    "/mock/payouts/"
                        + DevFixtureLoader.key(3)
                        + "?scenarioVersion=fixture-lookup-failure-v1",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(503);
    assertThat(count("mock_transactions")).isEqualTo(4);
    assertThat(count("mock_funds_reservations")).isEqualTo(4);
    assertThat(count("mock_cash_entries")).isEqualTo(3);
    assertThat(count("idempotency_records")).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM finance_mock.mock_transactions WHERE status='DECLINED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT balance FROM finance_mock.mock_accounts WHERE account_ref=?",
                Long.class,
                DevFixtureLoader.ACCOUNT))
        .isEqualTo(941_350);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_amount FROM finance_mock.mock_accounts WHERE account_ref=?",
                Long.class,
                DevFixtureLoader.ACCOUNT))
        .isZero();
    var before = jdbc.queryForList("SELECT * FROM finance_mock.mock_cash_entries ORDER BY id");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> loader.load());
      var second = executor.submit(() -> loader.load());
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    loader.runScenarios();
    assertThat(jdbc.queryForList("SELECT * FROM finance_mock.mock_cash_entries ORDER BY id"))
        .isEqualTo(before);
    assertThat(count("mock_transactions")).isEqualTo(4);

    headers.setBearerAuth("test-payment-credential-0123456789abcdef0123456");
    var approval =
        Map.of(
            "businessKey",
            UUID.randomUUID(),
            "method",
            "EASY_PAY",
            "accountRef",
            "fixture-easy-wallet",
            "fundingAccountRef",
            DevFixtureLoader.ACCOUNT,
            "amount",
            30_000,
            "scenarioVersion",
            "fixture-pg-normal-v1");
    var approved =
        http.postForEntity(
            "/mock/providers/EASY_PAY/payments", new HttpEntity<>(approval, headers), String.class);
    assertThat(approved.getStatusCode().value()).isEqualTo(202);
    var original = mapper.readTree(approved.getBody());
    assertThat(original.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(
            mapper.readTree(
                http.exchange(
                        approved.getHeaders().getLocation(),
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class)
                    .getBody()))
        .isEqualTo(original);
    loader.load();
    assertThat(
            jdbc.queryForObject(
                "SELECT balance FROM finance_mock.mock_accounts WHERE account_ref='fixture-easy-wallet'",
                Long.class))
        .isEqualTo(970_000);
    assertThat(
            mapper.readTree(
                http.postForEntity(
                        "/mock/providers/EASY_PAY/payments",
                        new HttpEntity<>(approval, headers),
                        String.class)
                    .getBody()))
        .isEqualTo(original);
    var refund = Map.of("businessKey", UUID.randomUUID(), "amount", 30_000);
    var refunded =
        http.postForEntity(
            "/mock/providers/EASY_PAY/payments/" + original.path("id").asText() + "/refunds",
            new HttpEntity<>(refund, headers),
            String.class);
    assertThat(refunded.getStatusCode().value()).isEqualTo(202);
    assertThat(mapper.readTree(refunded.getBody()).path("status").asText()).isEqualTo("SUCCEEDED");
    loader.load();
    assertThat(
            jdbc.queryForObject(
                "SELECT balance FROM finance_mock.mock_accounts WHERE account_ref='fixture-easy-wallet'",
                Long.class))
        .isEqualTo(1_000_000);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM finance_mock." + table, Integer.class);
  }
}
