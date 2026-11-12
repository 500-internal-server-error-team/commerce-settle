package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.port.SettlementEventOutbox;
import com.commercesettle.settlement.infrastructure.messaging.OutboxAppended;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment {@code JdbcPaymentEventOutbox}와 같은 저장 규칙이다. 집계별 저장을 커밋까지 직렬화해 늦은 낮은 버전의 삽입을 막고, 저장한 뒤 커밋
 * 후 Relay를 깨우는 신호를 낸다. Settlement가 발행하는 이벤트 종류만 받는다.
 */
@Repository
class JdbcSettlementEventOutbox implements SettlementEventOutbox {
  private static final Set<String> EVENT_TYPES =
      Set.of(
          "RefundReservationResult",
          "RefundLedgerReversed",
          "RefundReservationReleased",
          "SettlementNoticeRequested");

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private AsyncObservation observations = AsyncObservation.NOOP;
  private final ApplicationEventPublisher events;

  JdbcSettlementEventOutbox(
      NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.events = events;
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(EventEnvelope<?> event, UUID key) {
    if (!EVENT_TYPES.contains(event.eventType()) || event.eventVersion() != 1 || key == null) {
      throw new IllegalArgumentException("Invalid settlement outbox event");
    }
    byte[] body;
    try {
      body = mapper.writeValueAsBytes(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot serialize settlement outbox event");
    }
    applyTransactionLimits();
    var params =
        new MapSqlParameterSource("id", event.eventId())
            .addValue("type", event.eventType())
            .addValue("aggregate", event.aggregateId())
            .addValue("version", event.aggregateVersion())
            .addValue("topic", TOPIC)
            .addValue("occurred", event.occurredAt().toString())
            .addValue("body", body)
            .addValue("json", new String(body, StandardCharsets.UTF_8))
            .addValue("traceParent", observations.capture())
            .addValue("key", key.toString());
    // 집계별 append를 커밋까지 직렬화하여 늦은 낮은 버전의 삽입을 막는다.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:aggregate AS text), 0))", params);
    var existing =
        jdbc.query(
            "SELECT raw_body FROM settlement.outbox WHERE id = :id",
            params,
            (rs, row) -> rs.getBytes(1));
    if (!existing.isEmpty()) {
      requireSameBody(existing.getFirst(), body);
      return event.eventId();
    }
    Long latest =
        jdbc.queryForObject(
            "SELECT coalesce(max(aggregate_version), -1) FROM settlement.outbox"
                + " WHERE aggregate_id = :aggregate",
            params,
            Long.class);
    if (latest != null && event.aggregateVersion() < latest) {
      throw new IllegalArgumentException("Settlement outbox version moved backwards");
    }
    int inserted =
        jdbc.update(
            """
            INSERT INTO settlement.outbox (id,event_type,event_version,aggregate_id,aggregate_version,
                topic,occurred_at,message_key,payload,raw_body,trace_parent)
            VALUES (:id,:type,1,:aggregate,:version,:topic,
                CAST(:occurred AS timestamptz),:key,CAST(:json AS jsonb),:body,:traceParent)
            ON CONFLICT (id) DO NOTHING
            """,
            params);
    if (inserted == 0) {
      byte[] original =
          jdbc.queryForObject(
              "SELECT raw_body FROM settlement.outbox WHERE id = :id",
              Map.of("id", event.eventId()),
              byte[].class);
      requireSameBody(original, body);
    } else {
      events.publishEvent(new OutboxAppended());
    }
    return event.eventId();
  }

  private static void requireSameBody(byte[] original, byte[] body) {
    if (!Arrays.equals(original, body)) {
      throw new IllegalArgumentException("Outbox event ID has a different body");
    }
  }

  private void applyTransactionLimits() {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
  }
}
