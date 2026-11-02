package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.application.port.QuarantineStore.Delivery;
import com.commercesettle.settlement.application.port.QuarantineStore.Source;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL에서 격리 원문 저장과 DLT 발행 작업의 임대·재시도·소진을 확인한다. Kafka 발행은 격리 통합 시험이 맡는다. */
@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current=test-commerce-retention-credential-0123456789abcdef",
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Testcontainers
class QuarantineServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private static final long LEASE_MS = 60_000;

  @Autowired QuarantineService service;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void deleteRows() {
    jdbc.update("DELETE FROM settlement.inbox_quarantine_resumes");
    jdbc.update("DELETE FROM settlement.inbox_quarantine_deliveries");
    jdbc.update("DELETE FROM settlement.inbox_quarantines");
  }

  @Test
  @DisplayName("같은 위치의 같은 원문은 기존 격리로 연결하고, 다른 bytes는 거절한다")
  void captureIsIdempotentPerLocation() {
    UUID first = service.capture(source(5, "key", "body"));
    UUID again = service.capture(source(5, "key", "body"));

    assertThat(again).isEqualTo(first);
    assertThat(count("inbox_quarantines")).isEqualTo(1);
    assertThat(count("inbox_quarantine_deliveries")).isEqualTo(1);
    assertThatThrownBy(() -> service.capture(source(5, "key", "other")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("other");
  }

  @Test
  @DisplayName("발행에 실패하면 RETRY로 돌아가고, 시도 횟수를 다 쓰면 REVIEW_REQUIRED로 남는다")
  void failedDeliveryRetriesThenRequiresReview() {
    service.capture(source(1, null, "body"));
    Delivery delivery = service.claim(1, LEASE_MS, 10).get(0);
    assertThat(delivery.key()).isNull();
    assertThat(delivery.topic()).isEqualTo("commerce-settle.settlement.v1.dlt");

    assertThat(service.failed(delivery)).isTrue();
    assertThat(status()).isEqualTo("RETRY");
    // 다음 시도 시각은 지수 대기로 미래다.
    assertThat(service.claim(1, LEASE_MS, 10)).isEmpty();

    jdbc.update(
        "UPDATE settlement.inbox_quarantine_deliveries SET attempts = 10, next_run_at = now()");
    assertThat(service.claim(1, LEASE_MS, 10)).isEmpty();
    assertThat(status()).isEqualTo("REVIEW_REQUIRED");
  }

  @Test
  @DisplayName("임대가 바뀐 작업의 발행 결과는 저장하지 않는다")
  void staleResultIsIgnored() {
    service.capture(source(2, "key", "body"));
    Delivery delivery = service.claim(1, LEASE_MS, 10).get(0);

    assertThat(service.published(delivery)).isTrue();
    assertThat(status()).isEqualTo("PUBLISHED");
    assertThat(service.published(delivery)).isFalse();
    assertThat(service.claim(1, LEASE_MS, 10)).isEmpty();
  }

  @Test
  @DisplayName("재처리는 원문 버전이 같고 진행 중인 재처리가 없을 때만 원래 토픽으로 요청자·사유와 함께 만든다")
  void replayRequiresExpectedVersionAndNoActiveReplay() {
    UUID quarantineId = service.capture(source(3, "key", "body"));

    assertThatThrownBy(() -> service.requestReplay(quarantineId, 1, "ops@team", "FIXED"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.requestReplay(quarantineId, 0, "ops team", "FIXED"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requestReplay(quarantineId, 0, "ops@team", "fixed"))
        .isInstanceOf(IllegalArgumentException.class);

    UUID replay = service.requestReplay(quarantineId, 0, "ops@team", "PARSER_FIXED");

    assertThat(
            jdbc.queryForMap(
                "SELECT kind, topic, status, operator_id, reason_code"
                    + " FROM settlement.inbox_quarantine_deliveries WHERE id = ?",
                replay))
        .containsEntry("kind", "REPLAY")
        .containsEntry("topic", "commerce-settle.payment.v1")
        .containsEntry("status", "PENDING")
        .containsEntry("operator_id", "ops@team")
        .containsEntry("reason_code", "PARSER_FIXED");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM settlement.inbox_quarantines WHERE id = ?",
                Long.class,
                quarantineId))
        .isEqualTo(1);
    assertThatThrownBy(() -> service.requestReplay(quarantineId, 1, "ops@team", "AGAIN"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            service.claim(10, LEASE_MS, 10).stream()
                .filter(delivery -> delivery.id().equals(replay))
                .findFirst()
                .orElseThrow())
        .satisfies(
            delivery -> {
              assertThat(delivery.topic()).isEqualTo("commerce-settle.payment.v1");
              assertThat(delivery.key()).isEqualTo("key");
              assertThat(delivery.body()).isEqualTo("body".getBytes(StandardCharsets.UTF_8));
            });
  }

  @Test
  @DisplayName("소진된 발행 작업만 같은 버전으로 한 번 재개하고, 이전 시도 수와 요청자·사유를 이력에 남긴다")
  void resumeOnlyExhaustedDeliveryOnce() {
    service.capture(source(4, "key", "body"));
    UUID delivery =
        jdbc.queryForObject("SELECT id FROM settlement.inbox_quarantine_deliveries", UUID.class);
    assertThatThrownBy(() -> service.resumeDelivery(delivery, 0, "ops@team", "DLT_RECOVERED"))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update(
        "UPDATE settlement.inbox_quarantine_deliveries"
            + " SET status = 'REVIEW_REQUIRED', attempts = 10, version = 7");

    assertThatThrownBy(() -> service.resumeDelivery(delivery, 6, "ops@team", "DLT_RECOVERED"))
        .isInstanceOf(IllegalStateException.class);
    service.resumeDelivery(delivery, 7, "ops@team", "DLT_RECOVERED");

    assertThat(
            jdbc.queryForMap(
                "SELECT status, attempts, version FROM settlement.inbox_quarantine_deliveries"))
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("version", 8L);
    assertThat(
            jdbc.queryForMap(
                "SELECT delivery_id, expected_version, previous_attempts, operator_id, reason_code"
                    + " FROM settlement.inbox_quarantine_resumes"))
        .containsEntry("delivery_id", delivery)
        .containsEntry("expected_version", 7L)
        .containsEntry("previous_attempts", 10)
        .containsEntry("operator_id", "ops@team")
        .containsEntry("reason_code", "DLT_RECOVERED");
    assertThatThrownBy(() -> service.resumeDelivery(delivery, 7, "ops@team", "DLT_RECOVERED"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("inbox_quarantine_resumes")).isEqualTo(1);
  }

  private static Source source(long offset, String key, String body) {
    return new Source(
        "settlement.v1",
        "commerce-settle.payment.v1",
        0,
        offset,
        key,
        body.getBytes(StandardCharsets.UTF_8));
  }

  private String status() {
    List<String> statuses =
        jdbc.queryForList(
            "SELECT status FROM settlement.inbox_quarantine_deliveries", String.class);
    assertThat(statuses).hasSize(1);
    return statuses.get(0);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM settlement." + table, Long.class);
  }
}
