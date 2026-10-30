package com.commercesettle.payment.infrastructure.persistence.inbox;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcInboxStore implements InboxStore {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final String consumer;

  public JdbcInboxStore(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper mapper,
      @Value("${spring.kafka.consumer.group-id:payment.v1}") String consumer) {
    if (consumer.isBlank() || consumer.length() > 100) {
      throw new IllegalArgumentException("Invalid inbox consumer");
    }
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.consumer = consumer;
  }

  @Override
  public void saveWaiting(EventEnvelope<OrderPaymentRequestedPayload> event, Source source) {
    byte[] body = source.body();
    try {
      EventEnvelope<OrderPaymentRequestedPayload> decoded =
          mapper.readValue(body, new TypeReference<>() {});
      if (!event.equals(decoded)) {
        throw new IllegalArgumentException("Inbox event does not match its source body");
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("Invalid inbox source body");
    }
    var params =
        new MapSqlParameterSource("id", UUID.randomUUID())
            .addValue("consumer", consumer)
            .addValue("eventId", event.eventId())
            .addValue("type", event.eventType())
            .addValue("eventVersion", event.eventVersion())
            .addValue("occurred", event.occurredAt().toString())
            .addValue("aggregate", event.aggregateId())
            .addValue("aggregateVersion", event.aggregateVersion())
            .addValue("body", body)
            .addValue("json", new String(body, StandardCharsets.UTF_8))
            .addValue("hash", hash(body))
            .addValue("topic", source.topic())
            .addValue("partition", source.partition())
            .addValue("offset", source.offset());
    applyTransactionLimits();
    int inserted =
        jdbc.update(
            """
        INSERT INTO payment.inbox (id,consumer,event_id,event_type,event_version,occurred_at,payload,
            raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,aggregate_version,status,next_run_at)
        VALUES (:id,:consumer,:eventId,:type,:eventVersion,CAST(:occurred AS timestamptz),CAST(:json AS jsonb),
            :body,:hash,:topic,:partition,:offset,:aggregate,:aggregateVersion,'WAITING',clock_timestamp())
        ON CONFLICT (consumer,event_id) DO NOTHING
        """,
            params);
    if (inserted == 1) {
      return;
    }
    var original =
        jdbc.queryForObject(
            "SELECT id,raw_body FROM payment.inbox WHERE consumer=:consumer AND event_id=:eventId",
            params,
            (rs, row) -> new Original(rs.getObject("id", UUID.class), rs.getBytes("raw_body")));
    if (original == null) {
      throw new IllegalStateException("Existing inbox record was not visible");
    }
    if (Arrays.equals(original.body(), body)) {
      return;
    }
    params.addValue("inboxId", original.id()).addValue("conflictId", UUID.randomUUID());
    // 충돌 행까지 커밋한 뒤 반환한다. 원래 Inbox·업무 결과를 덮어쓰지 않는다.
    jdbc.update(
        """
        INSERT INTO payment.inbox_conflicts (id,inbox_id,consumer,event_id,raw_body,body_hash,
            source_topic,source_partition,source_offset)
        VALUES (:conflictId,:inboxId,:consumer,:eventId,:body,:hash,:topic,:partition,:offset)
        ON CONFLICT (consumer,event_id,source_topic,source_partition,source_offset) DO NOTHING
        """,
        params);
  }

  @Override
  public Optional<WaitingInboxEvent> claimNextWaiting(Instant now) {
    return claimWaiting(null, now);
  }

  @Override
  public Optional<WaitingInboxEvent> claimWaitingEvent(UUID eventId, Instant now) {
    return claimWaiting(java.util.Objects.requireNonNull(eventId), now);
  }

  private Optional<WaitingInboxEvent> claimWaiting(UUID eventId, Instant now) {
    var params =
        new MapSqlParameterSource("consumer", consumer)
            .addValue("now", toTimestamp(now))
            .addValue("eventId", eventId, Types.OTHER);
    applyTransactionLimits();
    List<Original> rows =
        jdbc.query(
            """
            SELECT id, raw_body FROM payment.inbox
            WHERE consumer = :consumer AND status = 'WAITING' AND next_run_at <= :now
              AND (CAST(:eventId AS uuid) IS NULL OR event_id = :eventId)
            ORDER BY next_run_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """,
            params,
            (rs, row) -> new Original(rs.getObject("id", UUID.class), rs.getBytes("raw_body")));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Original row = rows.get(0);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(row.body(), new TypeReference<>() {});
      return Optional.of(new WaitingInboxEvent(row.id(), event));
    } catch (IOException exception) {
      // 저장 시 검증한 원문이므로 읽기 실패는 데이터 손상이다. 원문을 예외에 싣지 않는다.
      throw new IllegalStateException("Stored inbox body cannot be read: " + row.id());
    }
  }

  @Override
  public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
    finishWaiting(inboxId, "APPLIED", resultRef, now);
  }

  @Override
  public void markReviewRequired(UUID inboxId, Instant now) {
    finishWaiting(inboxId, "REVIEW_REQUIRED", null, now);
  }

  @Override
  public Optional<OrderPaymentRequestedPayload> findOriginalRequest(UUID paymentId) {
    List<Original> rows =
        jdbc.query(
            """
            SELECT id, raw_body FROM payment.inbox
            WHERE consumer = :consumer AND status = 'APPLIED' AND result_ref = :paymentId
            ORDER BY created_at, id
            LIMIT 1
            """,
            new MapSqlParameterSource("consumer", consumer).addValue("paymentId", paymentId),
            (rs, row) -> new Original(rs.getObject("id", UUID.class), rs.getBytes("raw_body")));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Original row = rows.get(0);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(row.body(), new TypeReference<>() {});
      return Optional.of(event.payload());
    } catch (IOException exception) {
      throw new IllegalStateException("Stored inbox body cannot be read: " + row.id());
    }
  }

  private void finishWaiting(UUID inboxId, String status, UUID resultRef, Instant now) {
    int updated =
        jdbc.update(
            """
            UPDATE payment.inbox
            SET status = :status, result_ref = :resultRef, next_run_at = NULL,
                updated_at = :now, version = version + 1
            WHERE id = :inboxId AND status = 'WAITING'
            """,
            new MapSqlParameterSource("inboxId", inboxId)
                .addValue("status", status)
                .addValue("resultRef", resultRef, Types.OTHER)
                .addValue("now", toTimestamp(now)));
    if (updated != 1) {
      throw new IllegalStateException("Inbox record is not waiting: " + inboxId);
    }
  }

  private static OffsetDateTime toTimestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private void applyTransactionLimits() {
    jdbc.getJdbcTemplate()
        .queryForList(
            """
        SELECT set_config(budget.name,
            least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),
                budget.timeout_ms)::text,true)
        FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000))
            AS budget(name,timeout_ms)
        JOIN pg_settings settings ON settings.name=budget.name
        """);
  }

  private static String hash(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private record Original(UUID id, byte[] body) {}
}
