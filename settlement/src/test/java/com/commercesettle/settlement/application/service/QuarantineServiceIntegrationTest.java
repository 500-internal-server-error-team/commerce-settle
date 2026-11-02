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
