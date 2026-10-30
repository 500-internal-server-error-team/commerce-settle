package com.commercesettle.financemock.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.dev-fixtures.enabled=true"
    })
@Testcontainers
class DevFixtureIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  @Autowired DevFixtureLoader loader;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestRestTemplate http;

  @Test
  void createsActualPayoutRecordsAndPreservesBalanceAcrossResume() throws Exception {
    HttpHeaders headers = new HttpHeaders();
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
            http.getForEntity(
                    "/mock/payouts/"
                        + DevFixtureLoader.key(2)
                        + "?scenarioVersion=fixture-response-loss-v1",
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            http.getForEntity(
                    "/mock/payouts/"
                        + DevFixtureLoader.key(3)
                        + "?scenarioVersion=fixture-lookup-failure-v1",
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
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM finance_mock." + table, Integer.class);
  }
}
