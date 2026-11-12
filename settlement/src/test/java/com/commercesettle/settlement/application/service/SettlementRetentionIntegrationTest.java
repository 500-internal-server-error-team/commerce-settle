package com.commercesettle.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
      "app.internal-auth.senders.gateway.current=test-gateway-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:commerce].current="
          + SettlementRetentionIntegrationTest.CREDENTIAL,
      "settlement.payout.mock-credential=test-settlement-credential-0123456789abcdef0123",
      "spring.kafka.admin.auto-create=false",
      "settlement.daily-batch.schedule-enabled=false",
      "settlement.payout-worker.enabled=false",
      "settlement.inbox-processor.enabled=false"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SettlementRetentionIntegrationTest {
  static final String CREDENTIAL = "test-commerce-retention-credential-0123456789abcdef";
  private static final Instant CLOSED = Instant.parse("2026-08-02T01:00:00.123456789Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  @Autowired SettlementBatchRepository batches;
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;

  @Test
  @DisplayName("배치 완료 시각을 DB 정밀도로 고정하고 내부 HTTP에서 미종결·부재와 구분해 반환한다")
  void returnsImmutableClosureFromActualDatabaseAndHttp() {
    var done = batch();
    done.startProcessing(CLOSED.minusSeconds(1));
    done.complete(CLOSED);
    batches.save(done);
    var pending = batches.save(batch());
    UUID absent = UUID.randomUUID();

    var response = lookup(CREDENTIAL, List.of(done.getId(), pending.getId(), absent));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var values = response.getBody();
    assertThat(values.size()).isEqualTo(2);
    var closures = new java.util.HashMap<UUID, JsonNode>();
    values.forEach(
        value -> closures.put(UUID.fromString(value.required("batchId").textValue()), value));
    assertThat(closures.get(done.getId()).required("closedAt").textValue())
        .isEqualTo("2026-08-02T10:00:00.123456+09:00");
    assertThat(
            java.time.OffsetDateTime.parse(
                    closures.get(done.getId()).required("closedAt").textValue())
                .toInstant())
        .isEqualTo(CLOSED.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(closures.get(pending.getId()).required("closedAt").isNull()).isTrue();
    assertThat(closures.get(done.getId()).required("sellerId").textValue())
        .isEqualTo(done.getSellerId().toString());
    assertThatThrownBy(() -> done.requireReview(CLOSED.plusSeconds(1)))
        .isInstanceOf(RuntimeException.class);
    assertThat(batches.findById(done.getId()).orElseThrow().getClosedAt())
        .isEqualTo(done.getClosedAt());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE settlement.settlement_batches SET closed_at=NULL WHERE id=?",
                    done.getId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("정산 원천 조회는 Commerce 전용 자격만 허용하고 빈 요청과 과도한 묶음을 거절한다")
  void restrictsCallerAndRequestBounds() {
    assertThat(lookup(null, List.of(UUID.randomUUID())).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            lookup("test-gateway-credential-0123456789abcdef0123", List.of(UUID.randomUUID()))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    var invalid = lookup(CREDENTIAL, List.of());
    assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(invalid.getBody().required("errors").get(0).required("field").textValue())
        .isEqualTo("batchIds");
    UUID duplicate = UUID.randomUUID();
    assertThat(lookup(CREDENTIAL, List.of(duplicate, duplicate)).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            lookup(
                    CREDENTIAL,
                    java.util.stream.IntStream.range(0, 1001)
                        .mapToObj(i -> UUID.randomUUID())
                        .toList())
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private SettlementBatch batch() {
    return SettlementBatch.reserve(
        UUID.randomUUID(),
        LocalDate.of(2026, 8, 1),
        "retention-test-v1",
        5000,
        CLOSED.minusSeconds(2));
  }

  private org.springframework.http.ResponseEntity<JsonNode> lookup(
      String credential, List<UUID> ids) {
    var headers = new HttpHeaders();
    if (credential != null) headers.setBearerAuth(credential);
    return http.exchange(
        "/internal/settlement-retention",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("batchIds", ids), headers),
        JsonNode.class);
  }
}
