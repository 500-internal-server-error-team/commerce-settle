package com.commercesettle.payment.infrastructure.persistence.inbox;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor.Source;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
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
