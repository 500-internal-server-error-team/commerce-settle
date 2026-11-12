package com.commercesettle.commerce.infrastructure.persistence.outbox;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.commerce.application.port.OrderEventOutbox;
import com.commercesettle.commerce.infrastructure.messaging.OutboxAppended;
import com.commercesettle.commerce.infrastructure.persistence.TransactionLimits;
import com.commercesettle.observability.AsyncObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOrderEventOutbox implements OrderEventOutbox {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private AsyncObservation observations = AsyncObservation.NOOP;
  private final ApplicationEventPublisher events;

  public JdbcOrderEventOutbox(
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
  public UUID appendPaymentRequested(EventEnvelope<OrderPaymentRequestedPayload> event) {
    return appendPaymentRequested(event, null);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentRequested(
      EventEnvelope<OrderPaymentRequestedPayload> event, Instant orderCreatedAt) {
    if (!"OrderPaymentRequested".equals(event.eventType())
        || event.eventVersion() != 1
        || !event.aggregateId().equals(event.payload().orderId())
        || event.aggregateVersion() != event.payload().orderVersion()) {
      throw new IllegalArgumentException("Invalid order outbox event");
    }
    return append(event, event.payload().orderId(), orderCreatedAt);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(EventEnvelope<?> event, UUID messageKey) {
    return append(event, messageKey, null);
  }

  private UUID append(EventEnvelope<?> event, UUID messageKey, Instant orderCreatedAt) {
    if (event.eventVersion() != 1
        || !java.util.Set.of(
                "OrderPaymentRequested",
                "PaymentCancellationRequested",
                "RefundApproved",
                "RefundOrderRestored",
                "PurchaseConfirmed",
                "MileageAccrualScheduled",
                "MileageAccrualConfirmed")
            .contains(event.eventType())) throw new IllegalArgumentException("Invalid order event");
    byte[] body;
    try {
      body = mapper.writeValueAsBytes(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot serialize order outbox event");
    }
    TransactionLimits.apply(jdbc.getJdbcOperations());
    var params =
        new MapSqlParameterSource("id", event.eventId())
            .addValue("aggregate", event.aggregateId())
            .addValue("version", event.aggregateVersion())
            .addValue("type", event.eventType())
            .addValue("occurred", event.occurredAt().toString())
            .addValue("body", body)
            .addValue("json", new String(body, StandardCharsets.UTF_8))
            .addValue("traceParent", observations.capture())
            .addValue(
                "orderCreatedAt",
                orderCreatedAt == null ? null : orderCreatedAt.atOffset(ZoneOffset.UTC))
            .addValue("key", messageKey.toString());
    // 집계별 append를 커밋까지 직렬화하여 늦은 낮은 버전의 삽입을 막는다.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:aggregate AS text), 0))", params);
    // 잠금을 얻은 뒤 시작한 한 문장에서 최대 버전 확인과 삽입을 함께 한다. 삽입하지 않은 경우에만 기존 원문을
    // 다시 읽어, 같은 ID의 같은 본문은 버전과 관계없이 기존 처리로 인정하고 다른 본문·역행 버전은 거절한다.
    var outcome =
        jdbc.queryForMap(
            """
            WITH latest AS (
                SELECT coalesce(max(aggregate_version), -1) AS version FROM commerce.outbox
                WHERE aggregate_id = :aggregate
            ), inserted AS (
                INSERT INTO commerce.outbox (id,event_type,event_version,aggregate_id,aggregate_version,
                    topic,occurred_at,message_key,payload,raw_body,trace_parent,order_accepted_at)
                SELECT CAST(:id AS uuid),CAST(:type AS varchar),1,CAST(:aggregate AS uuid),
                    CAST(:version AS bigint),'commerce-settle.commerce.v1',CAST(:occurred AS timestamptz),
                    CAST(:key AS varchar),CAST(:json AS jsonb),CAST(:body AS bytea),
                    CAST(:traceParent AS varchar),CAST(:orderCreatedAt AS timestamptz)
                FROM latest WHERE latest.version <= CAST(:version AS bigint)
                ON CONFLICT (id) DO NOTHING
                RETURNING id
            )
            SELECT (SELECT count(*) FROM inserted) AS inserted,
                (SELECT version FROM latest) AS latest
            """,
            params);
    if (((Number) outcome.get("inserted")).longValue() == 1) {
      events.publishEvent(new OutboxAppended());
      return event.eventId();
    }
    var existing =
        jdbc.query(
            "SELECT raw_body FROM commerce.outbox WHERE id = :id",
            params,
            (rs, row) -> rs.getBytes(1));
    if (existing.isEmpty()) {
      if (event.aggregateVersion() < ((Number) outcome.get("latest")).longValue()) {
        throw new IllegalArgumentException("Order outbox version moved backwards");
      }
      throw new IllegalStateException("Existing outbox event was not visible");
    }
    requireSameBody(existing.getFirst(), body);
    return event.eventId();
  }

  private static void requireSameBody(byte[] original, byte[] body) {
    if (!Arrays.equals(original, body)) {
      throw new IllegalArgumentException("Outbox event ID has a different body");
    }
  }
}
