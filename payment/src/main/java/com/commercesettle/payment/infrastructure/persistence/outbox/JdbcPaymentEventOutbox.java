package com.commercesettle.payment.infrastructure.persistence.outbox;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.infrastructure.messaging.OutboxAppended;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPaymentEventOutbox implements PaymentEventOutbox {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ApplicationEventPublisher events;

  public JdbcPaymentEventOutbox(
      NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.events = events;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentAccepted(EventEnvelope<PaymentAcceptedPayload> event) {
    // aggregateVersion은 결제 버전이므로 payload의 주문 버전과 비교하지 않는다.
    if (!"PaymentAccepted".equals(event.eventType())
        || event.eventVersion() != 1
        || !event.aggregateId().equals(event.payload().paymentId())) {
      throw new IllegalArgumentException("Invalid payment outbox event");
    }
    byte[] body;
    try {
      body = mapper.writeValueAsBytes(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot serialize payment outbox event");
    }
    applyTransactionLimits();
    var params =
        new MapSqlParameterSource("id", event.eventId())
            .addValue("aggregate", event.aggregateId())
            .addValue("version", event.aggregateVersion())
            .addValue("occurred", event.occurredAt().toString())
            .addValue("body", body)
            .addValue("json", new String(body, StandardCharsets.UTF_8))
            .addValue("key", event.payload().paymentId().toString());
    // 집계별 append를 커밋까지 직렬화하여 늦은 낮은 버전의 삽입을 막는다.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:aggregate AS text), 0))", params);
    var existing =
        jdbc.query(
            "SELECT raw_body FROM payment.outbox WHERE id = :id",
            params,
            (rs, row) -> rs.getBytes(1));
    if (!existing.isEmpty()) {
      requireSameBody(existing.getFirst(), body);
      return event.eventId();
    }
    Long latest =
        jdbc.queryForObject(
            "SELECT coalesce(max(aggregate_version), -1) FROM payment.outbox WHERE aggregate_id ="
                + " :aggregate",
            params,
            Long.class);
    if (latest != null && event.aggregateVersion() < latest) {
      throw new IllegalArgumentException("Payment outbox version moved backwards");
    }
    int inserted =
        jdbc.update(
            """
            INSERT INTO payment.outbox (id,event_type,event_version,aggregate_id,aggregate_version,
                topic,occurred_at,message_key,payload,raw_body)
            VALUES (:id,'PaymentAccepted',1,:aggregate,:version,'commerce-settle.payment.v1',
                CAST(:occurred AS timestamptz),:key,CAST(:json AS jsonb),:body)
            ON CONFLICT (id) DO NOTHING
            """,
            params);
    if (inserted == 0) {
      byte[] original =
          jdbc.queryForObject(
              "SELECT raw_body FROM payment.outbox WHERE id = :id",
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
}
