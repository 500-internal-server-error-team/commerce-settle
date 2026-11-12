package com.commercesettle.commerce.infrastructure.persistence.inbox;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.PaymentAcceptedPayload;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Source;
import com.commercesettle.commerce.application.port.PaymentAcceptanceInbox.Waiting;
import com.commercesettle.commerce.infrastructure.persistence.TransactionLimits;
import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.observability.AsyncObservation.Timing;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcPaymentAcceptanceInbox implements PaymentAcceptanceInbox {
  private static final String ACCEPTED_TYPE = "event_type = 'PaymentAccepted'";
  private static final String RESULT_TYPES =
      "event_type <> 'PaymentAccepted' AND event_type IN ('PaymentConfirmed','PaymentDeclined',"
          + "'PaymentCancelled','PaymentCancellationRejected','RefundAccepted','RefundCompleted',"
          + "'RefundFailed')";
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final String consumer;
  private AsyncObservation observations = AsyncObservation.NOOP;

  public JdbcPaymentAcceptanceInbox(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper mapper,
      @Value("${spring.kafka.consumer.group-id:commerce.v1}") String consumer) {
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
  public void saveWaiting(EventEnvelope<PaymentAcceptedPayload> event, Source source, Instant now) {
    save(event, source, now);
  }

  @Override
  public void saveResult(
      EventEnvelope<com.fasterxml.jackson.databind.JsonNode> event, Source source, Instant now) {
    save(event, source, now);
  }

  private void save(EventEnvelope<?> event, Source source, Instant now) {
    byte[] body = source.body();
    try {
      EventEnvelope<com.fasterxml.jackson.databind.JsonNode> decoded =
          mapper.readValue(body, new TypeReference<>() {});
      if (!event.eventId().equals(decoded.eventId())
          || !event.eventType().equals(decoded.eventType())
          || event.eventVersion() != decoded.eventVersion()
          || !event.occurredAt().isEqual(decoded.occurredAt())
          || !event.aggregateId().equals(decoded.aggregateId())
          || event.aggregateVersion() != decoded.aggregateVersion()
          || !mapper
              .valueToTree(event.payload())
              .equals(
                  event.payload() instanceof PaymentAcceptedPayload
                      ? mapper.valueToTree(
                          mapper.treeToValue(decoded.payload(), PaymentAcceptedPayload.class))
                      : decoded.payload())) {
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
            .addValue("traceParent", observations.capture())
            .addValue("hash", hash(body))
            .addValue("topic", source.topic())
            .addValue("partition", source.partition())
            .addValue("offset", source.offset())
            .addValue("now", toTimestamp(now));
    TransactionLimits.apply(jdbc.getJdbcOperations());
    int inserted =
        jdbc.update(
            """
        INSERT INTO commerce.inbox (trace_parent,id,consumer,event_id,event_type,event_version,occurred_at,payload,
            raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,aggregate_version,status,next_run_at,created_at,updated_at)
        VALUES (:traceParent,:id,:consumer,:eventId,:type,:eventVersion,CAST(:occurred AS timestamptz),CAST(:json AS jsonb),
            :body,:hash,:topic,:partition,:offset,:aggregate,:aggregateVersion,'WAITING',:now,:now,:now)
        ON CONFLICT (consumer,event_id) DO NOTHING
        """,
            params);
    if (inserted == 1) {
      return;
    }
    var original =
        jdbc.queryForObject(
            "SELECT id,raw_body FROM commerce.inbox WHERE consumer=:consumer AND event_id=:eventId",
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
        INSERT INTO commerce.inbox_conflicts (id,inbox_id,consumer,event_id,raw_body,body_hash,
            source_topic,source_partition,source_offset,created_at)
        VALUES (:conflictId,:inboxId,:consumer,:eventId,:body,:hash,:topic,:partition,:offset,:now)
        ON CONFLICT (consumer,event_id,source_topic,source_partition,source_offset) DO NOTHING
        """,
        params);
  }

  @Override
  public Optional<Waiting> claim(UUID eventId, Instant now) {
    var found = claimBody(eventId, now, true);
    if (found.isEmpty()) return Optional.empty();
    Work row = found.get();
    try {
      EventEnvelope<PaymentAcceptedPayload> event =
          mapper.readValue(row.body(), new TypeReference<>() {});
      return Optional.of(new Waiting(row.id(), event, row.traceParent(), row.timing()));
    } catch (IOException exception) {
      // 저장 시 검증한 원문이므로 읽기 실패는 데이터 손상이다. 원문을 예외에 싣지 않는다.
      throw new IllegalStateException("Stored inbox body cannot be read: " + row.id());
    }
  }

  @Override
  public Optional<WaitingResult> claimResult(UUID eventId, Instant now) {
    var found = claimBody(eventId, now, false);
    if (found.isEmpty()) return Optional.empty();
    try {
      EventEnvelope<com.fasterxml.jackson.databind.JsonNode> event =
          mapper.readValue(found.get().body(), new TypeReference<>() {});
      return Optional.of(
          new WaitingResult(
              found.get().id(), event, found.get().traceParent(), found.get().timing()));
    } catch (IOException e) {
      throw new IllegalStateException("Stored result cannot be read");
    }
  }

  private Optional<Work> claimBody(UUID eventId, Instant now, boolean accepted) {
    var params =
        new MapSqlParameterSource("consumer", consumer)
            .addValue("now", toTimestamp(now))
            .addValue("eventId", eventId, Types.OTHER);
    TransactionLimits.apply(jdbc.getJdbcOperations());
    // 종류 조건은 리터럴로 고정해 처리기별 WAITING 부분 인덱스(inbox_waiting_accepted·inbox_waiting_results)의
    // 조건을 함축하게 한다. 그래야 다른 처리기의 대기 행을 건너뛰며 훑지 않는다.
    List<Work> rows =
        jdbc.query(
            """
            SELECT id,raw_body,trace_parent,created_at,updated_at,next_run_at,attempts FROM commerce.inbox
            WHERE consumer = :consumer AND status = 'WAITING' AND next_run_at <= :now
              AND %s AND event_version = 1
              AND (CAST(:eventId AS uuid) IS NULL OR event_id = :eventId)
            ORDER BY next_run_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """
                .formatted(accepted ? ACCEPTED_TYPE : RESULT_TYPES),
            params,
            (rs, row) ->
                new Work(
                    rs.getObject("id", UUID.class),
                    rs.getBytes("raw_body"),
                    rs.getString("trace_parent"),
                    new Timing(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("next_run_at").toInstant(),
                        "WAITING_RETRY",
                        rs.getInt("attempts") + 1)));
    return rows.stream().findFirst();
  }

  @Override
  public void applied(UUID inboxId, UUID orderId, Instant now) {
    finish(inboxId, "APPLIED", orderId, null, now);
  }

  @Override
  public void review(UUID inboxId, String errorCode, Instant now) {
    finish(inboxId, "REVIEW_REQUIRED", null, errorCode, now);
  }

  @Override
  public void retry(UUID inboxId, String errorCode, Instant now) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    // 반영 트랜잭션이 롤백된 사이 다른 처리기가 완료했으면 변경하지 않는다.
    jdbc.update(
        """
        UPDATE commerce.inbox
        SET attempts = attempts + 1, last_error_code = :errorCode,
            status = CASE WHEN attempts + 1 >= 10 THEN 'REVIEW_REQUIRED' ELSE 'WAITING' END,
            next_run_at = CASE WHEN attempts + 1 >= 10 THEN NULL ELSE
                :now + make_interval(secs => least(60, power(2, least(attempts, 6)))::integer) END,
            updated_at = :now, version = version + 1
        WHERE id = :id AND consumer = :consumer AND status = 'WAITING'
        """,
        new MapSqlParameterSource("id", inboxId)
            .addValue("consumer", consumer)
            .addValue("errorCode", errorCode)
            .addValue("now", toTimestamp(now)));
  }

  private void finish(UUID id, String status, UUID resultRef, String errorCode, Instant now) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    int updated =
        jdbc.update(
            """
        UPDATE commerce.inbox
        SET status = :status, result_ref = :resultRef, next_run_at = NULL,
            attempts = attempts + 1, last_error_code = :errorCode,
            updated_at = :now, version = version + 1
        WHERE id = :id AND consumer = :consumer AND status = 'WAITING'
        """,
            new MapSqlParameterSource("id", id)
                .addValue("consumer", consumer)
                .addValue("status", status)
                .addValue("resultRef", resultRef, Types.OTHER)
                .addValue("errorCode", errorCode)
                .addValue("now", toTimestamp(now)));
    if (updated != 1) throw new IllegalStateException("Inbox is not waiting: " + id);
  }

  private static OffsetDateTime toTimestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static String hash(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private record Original(UUID id, byte[] body) {}

  private record Work(UUID id, byte[] body, String traceParent, Timing timing) {}
}
