package com.commercesettle.payment.infrastructure.persistence.inbox;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.application.port.InboxClaim;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcInboxStore implements InboxStore {
  static final String ATTEMPTS_EXHAUSTED = "PAYMENT_REQUEST_ATTEMPTS_EXHAUSTED";
  static final String BODY_UNREADABLE = "PAYMENT_REQUEST_BODY_UNREADABLE";

  private static final Logger LOG = LoggerFactory.getLogger(JdbcInboxStore.class);

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
  public void saveWaiting(EventEnvelope<?> event, Source source) {
    byte[] body = source.body();
    try {
      EventEnvelope<?> decoded = mapper.readValue(body, envelopeType(event.eventType()));
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
  public Optional<InboxClaim> claimNextWaiting(Instant now, Instant leaseUntil, int maxAttempts) {
    return claimWaiting(null, now, leaseUntil, maxAttempts);
  }

  @Override
  public Optional<InboxClaim> claimWaitingEvent(
      UUID eventId, Instant now, Instant leaseUntil, int maxAttempts) {
    return claimWaiting(Objects.requireNonNull(eventId), now, leaseUntil, maxAttempts);
  }

  private Optional<InboxClaim> claimWaiting(
      UUID eventId, Instant now, Instant leaseUntil, int maxAttempts) {
    if (!leaseUntil.isAfter(now) || maxAttempts < 1) {
      throw new IllegalArgumentException("Invalid inbox claim");
    }
    var params =
        new MapSqlParameterSource("consumer", consumer)
            .addValue("now", toTimestamp(now))
            .addValue("leaseUntil", toTimestamp(leaseUntil))
            .addValue("max", maxAttempts)
            .addValue("exhausted", ATTEMPTS_EXHAUSTED)
            .addValue("eventId", eventId, Types.OTHER);
    applyTransactionLimits();
    // 한 번에 한 행만 다룬다. 확인 대상으로 넘긴 행도 처리 1건으로 돌려줘 처리기의 배치 크기에 들어가게 한다.
    List<Claimed> rows =
        jdbc.query(
            """
              WITH next AS (
                  SELECT id FROM payment.inbox
                  WHERE consumer = :consumer AND status = 'WAITING' AND next_run_at <= :now
                    AND (CAST(:eventId AS uuid) IS NULL OR event_id = :eventId)
                  ORDER BY next_run_at, id
                  LIMIT 1
                  FOR UPDATE SKIP LOCKED
              )
              UPDATE payment.inbox i
              SET status = CASE WHEN i.attempts >= :max THEN 'REVIEW_REQUIRED' ELSE 'WAITING' END,
                  next_run_at = CASE WHEN i.attempts >= :max THEN NULL ELSE :leaseUntil END,
                  last_error_code = CASE WHEN i.attempts >= :max
                      THEN :exhausted ELSE i.last_error_code END,
                  attempts = CASE WHEN i.attempts >= :max THEN i.attempts ELSE i.attempts + 1 END,
                  updated_at = :now, version = i.version + 1
              FROM next WHERE i.id = next.id
              RETURNING i.id, i.event_id, i.event_type, i.version, i.attempts, i.status,
                  i.raw_body, i.created_at
              """,
            params,
            (rs, row) ->
                new Claimed(
                    rs.getObject("id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getLong("version"),
                    rs.getInt("attempts"),
                    rs.getString("status"),
                    rs.getBytes("raw_body"),
                    rs.getTimestamp("created_at").toInstant()));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Claimed row = rows.get(0);
    if (!"WAITING".equals(row.status())) {
      // 마지막 시도가 결과를 남기지 못하고 끝났다. 상한을 넘겨 다시 처리하지 않는다.
      logReview(row, ATTEMPTS_EXHAUSTED);
      return Optional.of(new InboxClaim.Reviewed(row.id(), ATTEMPTS_EXHAUSTED));
    }
    try {
      EventEnvelope<?> event = mapper.readValue(row.body(), envelopeType(row.eventType()));
      return Optional.of(
          new InboxClaim.Claimed(
              new WaitingInboxEvent(
                  row.id(), row.version(), row.attempts(), row.registeredAt(), event)));
    } catch (IOException | IllegalArgumentException exception) {
      // 저장 때 검증한 원문이므로 읽기 실패는 데이터 손상이나 계약 변경이다. 다시 읽어도 같으므로 재시도하지 않는다.
      finishWaiting(row.id(), "REVIEW_REQUIRED", null, BODY_UNREADABLE, now);
      logReview(row, BODY_UNREADABLE);
      return Optional.of(new InboxClaim.Reviewed(row.id(), BODY_UNREADABLE));
    }
  }

  @Override
  public void lockOrder(UUID orderId) {
    applyTransactionLimits();
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
        new MapSqlParameterSource("key", "payment-order:" + Objects.requireNonNull(orderId)));
  }

  @Override
  public boolean lockClaimed(UUID inboxId, long version) {
    applyTransactionLimits();
    return !jdbc.queryForList(
            """
            SELECT id FROM payment.inbox
            WHERE id = :inboxId AND version = :version AND status = 'WAITING'
            FOR UPDATE
            """,
            new MapSqlParameterSource("inboxId", inboxId).addValue("version", version))
        .isEmpty();
  }

  @Override
  public void markApplied(UUID inboxId, UUID resultRef, Instant now) {
    finishWaiting(inboxId, "APPLIED", resultRef, null, now);
  }

  @Override
  public void markReviewRequired(UUID inboxId, String errorCode, Instant now) {
    finishWaiting(inboxId, "REVIEW_REQUIRED", null, requireErrorCode(errorCode), now);
  }

  @Override
  public boolean recordFailure(
      UUID inboxId, long version, String errorCode, Instant retryAt, Instant now) {
    var params =
        new MapSqlParameterSource("inboxId", inboxId)
            .addValue("version", version)
            .addValue("errorCode", requireErrorCode(errorCode))
            .addValue(
                "retryAt",
                retryAt == null ? null : toTimestamp(retryAt),
                Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("now", toTimestamp(now));
    applyTransactionLimits();
    // 선점 때 다음 처리 시각을 임대 만료로 미뤘으므로 임대 안에서는 다른 처리기가 이 행을 잡지 않는다.
    // 임대가 끝나 다른 처리기가 이미 선점했다면 버전이 바뀌어 기록하지 않는다.
    if (!lockClaimed(inboxId, version)) {
      return false;
    }
    return jdbc.update(
            """
            UPDATE payment.inbox
            SET last_error_code = :errorCode,
                status = CASE WHEN CAST(:retryAt AS timestamptz) IS NULL
                    THEN 'REVIEW_REQUIRED' ELSE 'WAITING' END,
                next_run_at = :retryAt, updated_at = :now, version = version + 1
            WHERE id = :inboxId AND version = :version AND status = 'WAITING'
            """,
            params)
        == 1;
  }

  @Override
  public Optional<OrderPaymentRequestedPayload> findOriginalRequest(UUID paymentId) {
    List<Original> rows =
        jdbc.query(
            """
            SELECT id, raw_body FROM payment.inbox
            WHERE consumer = :consumer AND result_ref = :paymentId
              AND event_type = 'OrderPaymentRequested'
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

  @Override
  public Optional<UUID> findEarlyCancellation(UUID orderId, UUID paymentCycleId) {
    return jdbc
        .query(
            """
            SELECT id FROM payment.inbox
            WHERE consumer = :consumer AND status = 'APPLIED' AND result_ref IS NULL
              AND event_type = 'PaymentCancellationRequested' AND aggregate_id = :orderId
              AND payload -> 'payload' ->> 'paymentCycleId' = :cycle
            ORDER BY created_at, id
            LIMIT 1
            """,
            new MapSqlParameterSource("consumer", consumer)
                .addValue("orderId", orderId)
                .addValue("cycle", paymentCycleId.toString()),
            (rs, row) -> rs.getObject("id", UUID.class))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<PaymentRequest> findPaymentRequest(UUID orderId, UUID paymentCycleId) {
    List<Original> rows =
        jdbc.query(
            """
            SELECT id, raw_body FROM payment.inbox
            WHERE consumer = :consumer AND event_type = 'OrderPaymentRequested'
              AND aggregate_id = :orderId AND payload -> 'payload' ->> 'paymentCycleId' = :cycle
            ORDER BY created_at, id
            LIMIT 1
            """,
            new MapSqlParameterSource("consumer", consumer)
                .addValue("orderId", orderId)
                .addValue("cycle", paymentCycleId.toString()),
            (rs, row) -> new Original(rs.getObject("id", UUID.class), rs.getBytes("raw_body")));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Original row = rows.get(0);
    try {
      EventEnvelope<OrderPaymentRequestedPayload> event =
          mapper.readValue(row.body(), new TypeReference<>() {});
      return Optional.of(new PaymentRequest(row.id(), event.payload()));
    } catch (IOException exception) {
      throw new IllegalStateException("Stored payment request cannot be read: " + row.id());
    }
  }

  @Override
  public void linkPaymentRequest(UUID inboxId, UUID paymentId, Instant now) {
    int updated =
        jdbc.update(
            """
        UPDATE payment.inbox SET result_ref = :paymentId,
            status = CASE WHEN status = 'WAITING' THEN 'APPLIED' ELSE status END,
            next_run_at = CASE WHEN status = 'WAITING' THEN NULL ELSE next_run_at END,
            updated_at = :now, version = version + 1
        WHERE id = :id AND consumer = :consumer AND event_type = 'OrderPaymentRequested'
          AND status IN ('WAITING', 'REVIEW_REQUIRED', 'APPLIED')
          AND (result_ref IS NULL OR result_ref = :paymentId)
        """,
            new MapSqlParameterSource("id", inboxId)
                .addValue("consumer", consumer)
                .addValue("paymentId", Objects.requireNonNull(paymentId))
                .addValue("now", toTimestamp(now)));
    if (updated != 1) {
      throw new IllegalStateException("Payment request cannot be linked: " + inboxId);
    }
  }

  @Override
  public void linkResult(UUID inboxId, UUID resultRef, Instant now) {
    int updated =
        jdbc.update(
            """
            UPDATE payment.inbox SET result_ref = :resultRef, updated_at = :now,
                version = version + 1
            WHERE id = :inboxId AND status = 'APPLIED' AND result_ref IS NULL
            """,
            new MapSqlParameterSource("inboxId", inboxId)
                .addValue("resultRef", Objects.requireNonNull(resultRef))
                .addValue("now", toTimestamp(now)));
    if (updated != 1) {
      throw new IllegalStateException("Inbox record cannot be linked: " + inboxId);
    }
  }

  /** 저장·선점하는 이벤트 종류별 본문 타입. 처리하지 않는 종류는 받지 않는다. */
  private JavaType envelopeType(String eventType) {
    Class<?> payload =
        switch (eventType) {
          case "OrderPaymentRequested" -> OrderPaymentRequestedPayload.class;
          case "PaymentCancellationRequested" -> PaymentCancellationRequestedPayload.class;
          case "RefundApproved" -> RefundApprovedPayload.class;
          case "RefundOrderRestored" -> RefundOrderRestoredPayload.class;
          case "RefundReservationResult" -> RefundReservationResultPayload.class;
          case "RefundLedgerReversed" -> RefundLedgerReversedPayload.class;
          case "RefundReservationReleased" -> RefundReservationReleasedPayload.class;
          default -> throw new IllegalArgumentException("Unsupported inbox event type");
        };
    return mapper.getTypeFactory().constructParametricType(EventEnvelope.class, payload);
  }

  private void finishWaiting(
      UUID inboxId, String status, UUID resultRef, String errorCode, Instant now) {
    // 처리에 성공해도 이전 실패 사유는 지우지 않는다. 마지막 실패·거절 사유로 남긴다.
    int updated =
        jdbc.update(
            """
            UPDATE payment.inbox
            SET status = :status, result_ref = :resultRef, next_run_at = NULL,
                last_error_code = coalesce(:errorCode, last_error_code),
                updated_at = :now, version = version + 1
            WHERE id = :inboxId AND status = 'WAITING'
            """,
            new MapSqlParameterSource("inboxId", inboxId)
                .addValue("status", status)
                .addValue("resultRef", resultRef, Types.OTHER)
                .addValue("errorCode", errorCode, Types.VARCHAR)
                .addValue("now", toTimestamp(now)));
    if (updated != 1) {
      throw new IllegalStateException("Inbox record is not waiting: " + inboxId);
    }
  }

  private static String requireErrorCode(String errorCode) {
    if (errorCode == null || errorCode.isBlank() || errorCode.length() > 80) {
      throw new IllegalArgumentException("Invalid inbox error code");
    }
    return errorCode;
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

  private void logReview(Claimed row, String errorCode) {
    LOG.atWarn()
        .addKeyValue("event", "payment.accept")
        .addKeyValue("inboxId", row.id())
        .addKeyValue("eventId", row.eventId())
        .addKeyValue("attempts", row.attempts())
        .addKeyValue("result", "REVIEW_REQUIRED")
        .addKeyValue("errorCode", errorCode)
        .log("Payment request needs review");
  }

  private record Claimed(
      UUID id,
      UUID eventId,
      String eventType,
      long version,
      int attempts,
      String status,
      byte[] body,
      Instant registeredAt) {}
}
