package com.commercesettle.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.service.EventRecordQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JdbcEventRecordReader.class,
  EventRecordQueryService.class,
  EventRecordIntegrationTest.Config.class
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventRecordIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  static final Instant AT = Instant.parse("2026-11-05T03:00:00Z");
  @Autowired JdbcTemplate jdbc;
  @Autowired EventRecordQueryService service;
  @Autowired ObjectMapper mapper;
  @Autowired DataSource dataSource;

  @TestConfiguration
  static class Config {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper();
    }
  }

  @BeforeEach
  void clearOwnFixture() {
    jdbc.update("DELETE FROM commerce.inbox");
    jdbc.update("DELETE FROM commerce.outbox");
  }

  String envelope(UUID event, UUID aggregate, Map<String, String> body) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "eventId",
            event,
            "eventType",
            "PaymentConfirmed",
            "eventVersion",
            1,
            "aggregateId",
            aggregate,
            "aggregateVersion",
            1,
            "occurredAt",
            AT.toString(),
            "payload",
            body));
  }

  void outbox(UUID event, UUID aggregate, Map<String, String> body) throws Exception {
    String payload = envelope(event, aggregate, body);
    jdbc.update(
        """
        INSERT INTO commerce.outbox(id,event_type,event_version,aggregate_id,aggregate_version,topic,occurred_at,message_key,payload,raw_body)
        VALUES(?,'PaymentConfirmed',1,?,1,'commerce-settle.commerce.v1',?,'key',?::jsonb,?)
        """,
        event,
        aggregate,
        Timestamp.from(AT),
        payload,
        payload.getBytes(StandardCharsets.UTF_8));
  }

  void inbox(UUID event, UUID aggregate, UUID result, Map<String, String> body) throws Exception {
    String payload = envelope(event, aggregate, body);
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    jdbc.update(
        """
        INSERT INTO commerce.inbox(id,consumer,event_id,event_type,event_version,occurred_at,payload,raw_body,body_hash,
          source_topic,source_partition,source_offset,aggregate_id,aggregate_version,status,next_run_at,result_ref)
        VALUES(?,'commerce.v1',?,'PaymentConfirmed',1,?,?::jsonb,?,?,'commerce-settle.payment.v1',1,17,?,1,'WAITING',?,?)
        """,
        UUID.randomUUID(),
        event,
        Timestamp.from(AT),
        payload,
        bytes,
        hash,
        aggregate,
        Timestamp.from(AT.plusSeconds(60)),
        result);
  }

  @Test
  @DisplayName("같은 이벤트의 발행·수신과 업무 참조를 원문 없이 읽고 상태를 바꾸지 않는다")
  void readsBothSidesAndBusinessReferencesWithoutMutation() throws Exception {
    UUID event = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID();
    outbox(
        event,
        payment,
        Map.of(
            "orderId",
            order.toString().toUpperCase(),
            "requestId",
            "malformed-value",
            "secret",
            "must-not-return"));
    inbox(event, payment, order, Map.of("paymentId", payment.toString()));
    var snapshot = service.get(List.of(order));
    assertThat(snapshot.records()).hasSize(2);
    assertThat(snapshot.records())
        .extracting(e -> e.kind())
        .containsExactlyInAnyOrder("OUTBOX", "INBOX");
    assertThat(snapshot.records())
        .allSatisfy(
            e -> {
              assertThat(e.eventId()).isEqualTo(event);
              assertThat(e.referenceIds()).contains(order, payment);
            });
    assertThat(snapshot.truncated()).isFalse();
    assertThat(service.get(List.of(event)).records()).hasSize(2);
    assertThat(service.get(List.of(UUID.randomUUID())).records()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT status FROM commerce.outbox", String.class))
        .isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT status FROM commerce.inbox", String.class))
        .isEqualTo("WAITING");
    assertThat(jdbc.queryForObject("SELECT source_offset FROM commerce.inbox", Long.class))
        .isEqualTo(17L);
  }

  @Test
  @DisplayName("상한50을 넘으면 잘림을 표시하고 다른 거래를 섞지 않는다")
  void boundsSnapshotAndDoesNotMixOtherTransactions() throws Exception {
    UUID aggregate = UUID.randomUUID();
    for (int n = 0; n < 52; n++) outbox(UUID.randomUUID(), aggregate, Map.of());
    var snapshot = service.get(List.of(aggregate));
    assertThat(snapshot.limit()).isEqualTo(50);
    assertThat(snapshot.records()).hasSize(50);
    assertThat(snapshot.truncated()).isTrue();
    assertThat(snapshot.records())
        .allSatisfy(e -> assertThat(e.aggregateId()).isEqualTo(aggregate));
    assertThatThrownBy(() -> service.get(java.util.Collections.nCopies(21, aggregate)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("다른 트랜잭션의 테이블 잠금에서도 읽기 대기는3초로 제한한다")
  void boundsActualDatabaseLockWait() throws Exception {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("LOCK TABLE commerce.outbox IN ACCESS EXCLUSIVE MODE");
      }
      long start = System.nanoTime();
      try {
        assertThatThrownBy(() -> service.get(List.of(UUID.randomUUID())))
            .isInstanceOf(DataAccessException.class);
      } finally {
        connection.rollback();
      }
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
          .isLessThan(java.time.Duration.ofSeconds(6));
    }
  }
}
