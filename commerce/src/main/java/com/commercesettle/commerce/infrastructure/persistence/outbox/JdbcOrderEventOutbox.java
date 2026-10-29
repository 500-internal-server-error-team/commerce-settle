package com.commercesettle.commerce.infrastructure.persistence.outbox;

import com.commercesettle.commerce.application.event.EventEnvelope;
import com.commercesettle.commerce.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.commerce.application.port.OrderEventOutbox;
import com.commercesettle.commerce.infrastructure.messaging.OutboxAppended;
import com.commercesettle.commerce.infrastructure.persistence.TransactionLimits;
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
public class JdbcOrderEventOutbox implements OrderEventOutbox {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ApplicationEventPublisher events;

  public JdbcOrderEventOutbox(
      NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.events = events;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID appendPaymentRequested(EventEnvelope<OrderPaymentRequestedPayload> event) {
    if (!"OrderPaymentRequested".equals(event.eventType())
        || event.eventVersion() != 1
        || !event.aggregateId().equals(event.payload().orderId())
        || event.aggregateVersion() != event.payload().orderVersion()) {
      throw new IllegalArgumentException("Invalid order outbox event");
    }
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
            .addValue("occurred", event.occurredAt().toString())
            .addValue("body", body)
            .addValue("json", new String(body, StandardCharsets.UTF_8))
            .addValue("key", event.payload().orderId().toString());
    // 집계별 append를 커밋까지 직렬화하여 늦은 낮은 버전의 삽입을 막는다.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:aggregate AS text), 0))", params);
    var existing =
        jdbc.query(
            "SELECT raw_body FROM commerce.outbox WHERE id = :id",
            params,
            (rs, row) -> rs.getBytes(1));
    if (!existing.isEmpty()) {
      requireSameBody(existing.getFirst(), body);
      return event.eventId();
    }
    Long latest =
        jdbc.queryForObject(
            "SELECT coalesce(max(aggregate_version), -1) FROM commerce.outbox WHERE aggregate_id ="
                + " :aggregate",
            params,
            Long.class);
    if (latest != null && event.aggregateVersion() < latest) {
      throw new IllegalArgumentException("Order outbox version moved backwards");
    }
    int inserted =
        jdbc.update(
            """
            INSERT INTO commerce.outbox (id,event_type,event_version,aggregate_id,aggregate_version,
                topic,occurred_at,message_key,payload,raw_body)
            VALUES (:id,'OrderPaymentRequested',1,:aggregate,:version,'commerce-settle.commerce.v1',
                CAST(:occurred AS timestamptz),:key,CAST(:json AS jsonb),:body)
            ON CONFLICT (id) DO NOTHING
            """,
            params);
    if (inserted == 0) {
      byte[] original =
          jdbc.queryForObject(
              "SELECT raw_body FROM commerce.outbox WHERE id = :id",
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
}
