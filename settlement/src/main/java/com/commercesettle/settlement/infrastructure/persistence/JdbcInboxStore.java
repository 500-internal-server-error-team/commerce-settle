package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.settlement.application.event.RefundReleaseRequestedPayload;
import com.commercesettle.settlement.application.event.RefundReservationRequestedPayload;
import com.commercesettle.settlement.application.port.InboxStore;
import com.commercesettle.settlement.domain.model.InboxReviewReason;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
  private AsyncObservation observations = AsyncObservation.NOOP;

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

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
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
            .addValue("traceParent", observations.capture())
            .addValue(
                "orderAcceptedAt",
                source.orderAcceptedAt() == null
                    ? null
                    : source.orderAcceptedAt().atOffset(ZoneOffset.UTC))
            .addValue("hash", hash(body))
            .addValue("topic", source.topic())
            .addValue("partition", source.partition())
            .addValue("offset", source.offset());
    applyTransactionLimits();
    int inserted =
        jdbc.update(
            """
            INSERT INTO settlement.inbox (order_accepted_at,trace_parent,id,consumer,event_id,event_type,event_version,occurred_at,payload,
                raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,aggregate_version,status)
            VALUES (:orderAcceptedAt,:traceParent,:id,:consumer,:eventId,:type,:eventVersion,CAST(:occurred AS timestamptz),CAST(:json AS jsonb),
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
  public Optional<Pending> claimNextWaiting() {
    applyTransactionLimits();
    List<Stored> rows =
        jdbc.query(
            """
            SELECT id,event_type,raw_body,trace_parent,created_at,updated_at,next_run_at,status,attempts FROM settlement.inbox
            WHERE consumer = :consumer AND status = 'WAITING' AND next_run_at <= clock_timestamp()
            ORDER BY next_run_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """,
            new MapSqlParameterSource("consumer", consumer),
            (rs, row) -> readStored(rs));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(decode(rows.getFirst()));
  }

  private Pending decode(Stored row) {
    try {
      return switch (row.eventType()) {
        case "PurchaseConfirmed" ->
            new Pending.Purchase(
                row.id(),
                mapper.<EventEnvelope<PurchaseConfirmedPayload>>readValue(
                    row.body(), new TypeReference<>() {}),
                row.traceParent(),
                row.timing());
        case "RefundReservationRequested" ->
            new Pending.RefundReservation(
                row.id(),
                mapper.<EventEnvelope<RefundReservationRequestedPayload>>readValue(
                    row.body(), new TypeReference<>() {}),
                row.traceParent(),
                row.timing());
        case "RefundFinancialCompleted" ->
            new Pending.RefundCompleted(
                row.id(),
                mapper.<EventEnvelope<RefundFinancialCompletedPayload>>readValue(
                    row.body(), new TypeReference<>() {}),
                row.traceParent(),
                row.timing());
        case "RefundReleaseRequested" ->
            new Pending.RefundRelease(
                row.id(),
                mapper.<EventEnvelope<RefundReleaseRequestedPayload>>readValue(
                    row.body(), new TypeReference<>() {}),
                row.traceParent(),
                row.timing());
        default -> throw new IllegalStateException("Unsupported inbox event type");
      };
    } catch (IOException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored inbox body cannot be read");
    }
  }

  @Override
  public Optional<Review> lockReview(UUID inboxId) {
    applyTransactionLimits();
    return jdbc
        .query(
            "SELECT id,event_type,raw_body,trace_parent,created_at,updated_at,next_run_at,attempts,version,status,last_error_code FROM settlement.inbox WHERE id=:id AND consumer=:consumer FOR UPDATE",
            new MapSqlParameterSource("id", inboxId).addValue("consumer", consumer),
            (rs, n) ->
                new Review(
                    decode(readStored(rs)),
                    rs.getLong("version"),
                    rs.getString("status"),
                    rs.getString("last_error_code")))
        .stream()
        .findFirst();
  }

  @Override
  public void reopenReview(UUID inboxId, Instant now) {
    requireOne(
        jdbc.update(
            "UPDATE settlement.inbox SET status='RECEIVED',attempts=0,next_run_at=NULL,updated_at=:now,version=version+1 WHERE id=:id AND status='REVIEW_REQUIRED'",
            new MapSqlParameterSource("id", inboxId).addValue("now", now.atOffset(ZoneOffset.UTC))),
        inboxId);
  }

  @Override
  public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
    finish(inboxId, "APPLIED", resultRef, null, now);
  }

  @Override
  public void markReviewRequired(UUID inboxId, InboxReviewReason reason, Instant now) {
    finish(inboxId, "REVIEW_REQUIRED", null, java.util.Objects.requireNonNull(reason).name(), now);
  }

  @Override
  public boolean markWaiting(UUID inboxId, Duration baseDelay, int maxAttempts, Instant now) {
    // SET의 attempts는 갱신 전 값이다. 상한 판정과 대기 시간을 같은 문장에서 정해 롤백 뒤에도 횟수가 어긋나지 않는다.
    List<String> statuses =
        jdbc.query(
            """
            UPDATE settlement.inbox
            SET attempts = attempts + 1, last_error_code = 'PAYMENT_NOT_ARRIVED',
                status = CASE WHEN attempts + 1 >= :max THEN 'REVIEW_REQUIRED' ELSE 'WAITING' END,
                next_run_at = CASE WHEN attempts + 1 >= :max THEN NULL
                    ELSE clock_timestamp()
                        + make_interval(secs => least(60, :base * power(2, least(attempts, 6)))) END,
                updated_at = :now, version = version + 1
            WHERE id = :inboxId AND status IN ('RECEIVED', 'WAITING')
            RETURNING status
            """,
            new MapSqlParameterSource("inboxId", inboxId)
                .addValue("base", baseDelay.toMillis() / 1000.0)
                .addValue("max", maxAttempts)
                .addValue("now", now.atOffset(ZoneOffset.UTC)),
            (rs, row) -> rs.getString(1));
    requireOne(statuses.size(), inboxId);
    return "WAITING".equals(statuses.get(0));
  }

  @Override
  public List<UUID> reviveExhaustedPurchases(UUID paymentId, int maxAttempts, Instant now) {
    // 시도 상한 조건은 대기 중 결제가 도착한 뒤 다른 사유로 운영 확인에 남은 행(마지막 사유가 그대로 남음)을 거른다.
    // WAITING도 대상이다. 처리기가 잠근 마지막 판정 행은 커밋 전까지 WAITING으로 보이므로 이 문장이 잠금을 기다리고, 처리기가
    // REVIEW_REQUIRED로 커밋하면 바뀐 행으로 조건을 다시 검사해 되살린다. 수신 때 형식을 검사한 UUID라 값으로 비교한다.
    return jdbc.query(
        """
        UPDATE settlement.inbox
        SET status = 'WAITING', attempts = 0, last_error_code = 'PAYMENT_ARRIVED_LATE',
            next_run_at = clock_timestamp(), updated_at = :now, version = version + 1
        WHERE consumer = :consumer AND event_type = 'PurchaseConfirmed'
          AND (status = 'WAITING'
            OR (status = 'REVIEW_REQUIRED' AND last_error_code = 'PAYMENT_NOT_ARRIVED'
              AND attempts >= :max))
          AND (payload->'payload'->>'paymentId')::uuid = :paymentId
        RETURNING id
        """,
        new MapSqlParameterSource("consumer", consumer)
            .addValue("paymentId", paymentId)
            .addValue("max", maxAttempts)
            .addValue("now", now.atOffset(ZoneOffset.UTC)),
        (rs, row) -> rs.getObject(1, UUID.class));
  }

  @Override
  public Optional<EarlierRefundRequest> findEarlierRefundRequest(UUID refundId, UUID inboxId) {
    return jdbc
        .query(
            """
            SELECT status, (payload->'payload'->>'paymentId')::uuid AS payment_id,
                (payload->'payload'->>'netAmount')::bigint AS net_amount, result_ref
            FROM settlement.inbox
            WHERE consumer = :consumer AND event_type = 'RefundReservationRequested'
              AND (payload->'payload'->>'refundId')::uuid = :refundId
              AND (created_at, id) < (SELECT created_at, id FROM settlement.inbox WHERE id = :inboxId)
            ORDER BY created_at, id
            LIMIT 1
            """,
            new MapSqlParameterSource("consumer", consumer)
                .addValue("refundId", refundId)
                .addValue("inboxId", inboxId),
            (rs, row) ->
                new EarlierRefundRequest(
                    rs.getString("status"),
                    rs.getObject("payment_id", UUID.class),
                    rs.getLong("net_amount"),
                    rs.getObject("result_ref", UUID.class)))
        .stream()
        .findFirst();
  }

  private void finish(UUID inboxId, String status, UUID resultRef, String reason, Instant now) {
    int updated =
        jdbc.update(
            """
            UPDATE settlement.inbox
            SET status = :status, result_ref = :resultRef, next_run_at = NULL,
                last_error_code = coalesce(:reason,last_error_code),
                updated_at = :now, version = version + 1
            WHERE id = :inboxId AND status IN ('RECEIVED', 'WAITING')
            """,
            new MapSqlParameterSource("inboxId", inboxId)
                .addValue("status", status)
                .addValue("reason", reason, Types.VARCHAR)
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

  private Stored readStored(ResultSet rs) throws SQLException {
    var eligible = rs.getTimestamp("next_run_at");
    return new Stored(
        rs.getObject("id", UUID.class),
        rs.getString("event_type"),
        rs.getBytes("raw_body"),
        rs.getString("trace_parent"),
        new Timing(
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            eligible == null ? null : eligible.toInstant(),
            "WAITING".equals(rs.getString("status")) ? "WAITING_RETRY" : "unknown",
            rs.getInt("attempts") + 1));
  }

  private record Stored(
      UUID id, String eventType, byte[] body, String traceParent, Timing timing) {}
}
