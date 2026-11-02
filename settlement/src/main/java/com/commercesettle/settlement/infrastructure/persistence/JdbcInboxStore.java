package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.port.InboxStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment {@code JdbcInboxStore}와 같은 방식이다. 원본 bytes·SHA-256을 보존하고 같은 ID의 다른 bytes는 충돌 기록으로 격리한다. 잠금
 * 대기 1초·문장 5초 상한을 건다.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcInboxStore implements InboxStore {
  private final NamedParameterJdbcOperations jdbc;
  private final ObjectMapper mapper;
  private final String consumer;

  JdbcInboxStore(
      NamedParameterJdbcOperations jdbc,
      ObjectMapper mapper,
      @Value("${spring.kafka.consumer.group-id:settlement.v1}") String consumer) {
    if (consumer.isBlank() || consumer.length() > 100) {
      throw new IllegalArgumentException("Invalid inbox consumer");
    }
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.consumer = consumer;
  }

  @Override
  public Optional<UUID> receive(EventEnvelope<?> event, Source source) {
    byte[] body = source.body();
    UUID inboxId = UUID.randomUUID();
    var params =
        new MapSqlParameterSource("id", inboxId)
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
            INSERT INTO settlement.inbox (id,consumer,event_id,event_type,event_version,occurred_at,payload,
                raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,aggregate_version,status)
            VALUES (:id,:consumer,:eventId,:type,:eventVersion,CAST(:occurred AS timestamptz),CAST(:json AS jsonb),
                :body,:hash,:topic,:partition,:offset,:aggregate,:aggregateVersion,'RECEIVED')
            ON CONFLICT (consumer,event_id) DO NOTHING
            """,
            params);
    if (inserted == 1) {
      return Optional.of(inboxId);
    }
    Original original =
        jdbc.queryForObject(
            "SELECT id,raw_body FROM settlement.inbox WHERE consumer=:consumer AND event_id=:eventId",
            params,
            (rs, row) -> new Original(rs.getObject("id", UUID.class), rs.getBytes("raw_body")));
    if (original == null) {
      throw new IllegalStateException("Existing inbox record was not visible");
    }
    if (!Arrays.equals(original.body(), body)) {
      params.addValue("inboxId", original.id()).addValue("conflictId", UUID.randomUUID());
      jdbc.update(
          """
          INSERT INTO settlement.inbox_conflicts (id,inbox_id,consumer,event_id,raw_body,body_hash,
              source_topic,source_partition,source_offset)
          VALUES (:conflictId,:inboxId,:consumer,:eventId,:body,:hash,:topic,:partition,:offset)
          ON CONFLICT (consumer,event_id,source_topic,source_partition,source_offset) DO NOTHING
          """,
          params);
    }
    return Optional.empty();
  }

  @Override
  public Optional<WaitingPurchase> claimNextWaiting() {
    applyTransactionLimits();
    List<Original> rows =
        jdbc.query(
            """
            SELECT id, raw_body FROM settlement.inbox
            WHERE consumer = :consumer AND status = 'WAITING' AND next_run_at <= clock_timestamp()
            ORDER BY next_run_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """,
            new MapSqlParameterSource("consumer", consumer),
            (rs, row) -> new Original(rs.getObject("id", UUID.class), rs.getBytes("raw_body")));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Original row = rows.get(0);
    try {
      EventEnvelope<PurchaseConfirmedPayload> event =
          mapper.readValue(row.body(), new TypeReference<>() {});
      return Optional.of(new WaitingPurchase(row.id(), event));
    } catch (IOException exception) {
      // 수신 때 엄격하게 해석한 원문이므로 읽기 실패는 데이터 손상이다. 원문을 예외에 싣지 않는다.
      throw new IllegalStateException("Stored inbox body cannot be read: " + row.id());
    }
  }

  @Override
  public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
    finish(inboxId, "APPLIED", resultRef, now);
  }

  @Override
  public void markReviewRequired(UUID inboxId, Instant now) {
    finish(inboxId, "REVIEW_REQUIRED", null, now);
  }

  @Override
  public void markWaiting(UUID inboxId, Duration delay, Instant now) {
    int updated =
        jdbc.update(
            """
            UPDATE settlement.inbox
            SET status = 'WAITING', next_run_at = clock_timestamp() + make_interval(secs => :delay),
                updated_at = :now, version = version + 1
            WHERE id = :inboxId AND status IN ('RECEIVED', 'WAITING')
            """,
            new MapSqlParameterSource("inboxId", inboxId)
                .addValue("delay", delay.toMillis() / 1000.0)
                .addValue("now", now.atOffset(ZoneOffset.UTC)));
    requireOne(updated, inboxId);
  }

  private void finish(UUID inboxId, String status, UUID resultRef, Instant now) {
    int updated =
        jdbc.update(
            """
            UPDATE settlement.inbox
            SET status = :status, result_ref = :resultRef, next_run_at = NULL,
                updated_at = :now, version = version + 1
            WHERE id = :inboxId AND status IN ('RECEIVED', 'WAITING')
            """,
            new MapSqlParameterSource("inboxId", inboxId)
                .addValue("status", status)
                .addValue("resultRef", resultRef, Types.OTHER)
                .addValue("now", now.atOffset(ZoneOffset.UTC)));
    requireOne(updated, inboxId);
  }

  private static void requireOne(int updated, UUID inboxId) {
    if (updated != 1) {
      throw new IllegalStateException("Inbox record is not pending: " + inboxId);
    }
  }

  private void applyTransactionLimits() {
    jdbc.getJdbcOperations()
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
