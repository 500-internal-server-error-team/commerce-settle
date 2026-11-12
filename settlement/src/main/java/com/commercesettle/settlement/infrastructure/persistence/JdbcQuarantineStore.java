package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.port.QuarantineStore;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment {@code JdbcQuarantineStore}와 같은 방식이다. key는 UTF-8 bytes로 보존하고, 발행 결과는 임대·버전이 그대로일 때만 저장한다.
 * 잠금 대기 1초·문장 5초 상한을 건다.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcQuarantineStore implements QuarantineStore {
  static final String DLT_TOPIC = "commerce-settle.settlement.v1.dlt";

  private final NamedParameterJdbcOperations jdbc;

  JdbcQuarantineStore(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public UUID capture(Source source, String errorCode, Instant now) {
    applyTransactionLimits();
    var params =
        params(now)
            .addValue("id", UUID.randomUUID())
            .addValue("consumer", source.consumer())
            .addValue("topic", source.topic())
            .addValue("partition", source.partition())
            .addValue("offset", source.offset())
            .addValue("key", keyBytes(source.key()), Types.BINARY)
            .addValue("body", source.body(), Types.BINARY)
            .addValue("error", errorCode)
            .addValue("dlt", DLT_TOPIC);
    jdbc.update(
        """
        INSERT INTO settlement.inbox_quarantines
          (id,consumer,source_topic,source_partition,source_offset,message_key,raw_body,error_code,created_at)
        VALUES (:id,:consumer,:topic,:partition,:offset,:key,:body,:error,:now)
        ON CONFLICT (consumer,source_topic,source_partition,source_offset) DO NOTHING
        """,
        params);
    Original original =
        jdbc.queryForObject(
            """
            SELECT id,message_key,raw_body FROM settlement.inbox_quarantines
            WHERE consumer=:consumer AND source_topic=:topic
              AND source_partition=:partition AND source_offset=:offset
            """,
            params,
            (rs, row) ->
                new Original(
                    rs.getObject("id", UUID.class),
                    keyString(rs.getBytes("message_key")),
                    rs.getBytes("raw_body")));
    if (original == null
        || !Objects.equals(original.key(), source.key())
        || !Arrays.equals(original.body(), source.body())) {
      throw new IllegalStateException("Quarantine source location has different bytes");
    }
    params.addValue("id", original.id()).addValue("delivery", UUID.randomUUID());
    jdbc.update(
        """
        INSERT INTO settlement.inbox_quarantine_deliveries
          (id,quarantine_id,kind,topic,status,next_run_at,created_at,updated_at)
        VALUES (:delivery,:id,'DLT',:dlt,'PENDING',clock_timestamp(),:now,:now)
        ON CONFLICT (quarantine_id) WHERE kind='DLT' DO NOTHING
        """,
        params);
    return original.id();
  }

  @Override
  public Optional<UUID> requestReplay(
      UUID quarantineId, long expectedVersion, String operator, String reasonCode, Instant now) {
    applyTransactionLimits();
    var params =
        params(now)
            .addValue("id", quarantineId)
            .addValue("version", expectedVersion)
            .addValue("operator", operator)
            .addValue("reason", reasonCode)
            .addValue("delivery", UUID.randomUUID());
    var topics =
        jdbc.query(
            """
            UPDATE settlement.inbox_quarantines q SET version=version+1
            WHERE q.id=:id AND q.version=:version
              AND NOT EXISTS (SELECT 1 FROM settlement.inbox_quarantine_deliveries d
                WHERE d.quarantine_id=q.id AND d.kind='REPLAY' AND d.status IN ('PENDING','RUNNING','RETRY'))
            RETURNING source_topic
            """,
            params,
            (rs, row) -> rs.getString(1));
    if (topics.size() != 1) {
      return Optional.empty();
    }
    params.addValue("topic", topics.getFirst());
    jdbc.update(
        """
        INSERT INTO settlement.inbox_quarantine_deliveries
          (id,quarantine_id,kind,topic,status,operator_id,reason_code,next_run_at,created_at,updated_at)
        VALUES (:delivery,:id,'REPLAY',:topic,'PENDING',:operator,:reason,clock_timestamp(),:now,:now)
        """,
        params);
    return Optional.of((UUID) params.getValue("delivery"));
  }

  @Override
  public Optional<UUID> resumeDelivery(
      UUID deliveryId, long expectedVersion, String operator, String reasonCode, Instant now) {
    applyTransactionLimits();
    var params =
        params(now)
            .addValue("id", deliveryId)
            .addValue("version", expectedVersion)
            .addValue("operator", operator)
            .addValue("reason", reasonCode)
            .addValue("audit", UUID.randomUUID());
    var rows =
        jdbc.query(
            """
            SELECT attempts,version,status FROM settlement.inbox_quarantine_deliveries
            WHERE id=:id FOR UPDATE
            """,
            params,
            (rs, row) ->
                new Exhausted(
                    rs.getInt("attempts"), rs.getLong("version"), rs.getString("status")));
    if (rows.size() != 1
        || rows.getFirst().version() != expectedVersion
        || !"REVIEW_REQUIRED".equals(rows.getFirst().status())) {
      return Optional.empty();
    }
    params.addValue("attempts", rows.getFirst().attempts());
    jdbc.update(
        """
        INSERT INTO settlement.inbox_quarantine_resumes
          (id,delivery_id,expected_version,previous_attempts,operator_id,reason_code,created_at)
        VALUES (:audit,:id,:version,:attempts,:operator,:reason,:now)
        """,
        params);
    jdbc.update(
        """
        UPDATE settlement.inbox_quarantine_deliveries SET status='PENDING',attempts=0,
          next_run_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1
        WHERE id=:id AND version=:version AND status='REVIEW_REQUIRED'
        """,
        params);
    return Optional.of((UUID) params.getValue("audit"));
  }

  @Override
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts, Instant now) {
    applyTransactionLimits();
    var params =
        params(now)
            .addValue("limit", limit)
            .addValue("lease", leaseMs)
            .addValue("max", maxAttempts);
    jdbc.update(
        """
        WITH exhausted AS (
          SELECT id FROM settlement.inbox_quarantine_deliveries
          WHERE attempts>=:max AND ((status IN ('PENDING','RETRY') AND next_run_at<=clock_timestamp())
            OR (status='RUNNING' AND lease_until<=clock_timestamp()))
          ORDER BY next_run_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
        ) UPDATE settlement.inbox_quarantine_deliveries d
        SET status='REVIEW_REQUIRED',lease_until=NULL,updated_at=clock_timestamp(),version=version+1
        FROM exhausted e WHERE d.id=e.id
        """,
        params);
    return jdbc.query(
        """
        WITH candidates AS (
          SELECT id FROM settlement.inbox_quarantine_deliveries
          WHERE attempts<:max AND ((status IN ('PENDING','RETRY') AND next_run_at<=clock_timestamp())
            OR (status='RUNNING' AND lease_until<=clock_timestamp()))
          ORDER BY next_run_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
        ), claimed AS (
          UPDATE settlement.inbox_quarantine_deliveries d
          SET status='RUNNING',attempts=attempts+1,lease_until=clock_timestamp()+(:lease*interval '1 millisecond'),
            updated_at=clock_timestamp(),version=version+1
          FROM candidates c WHERE d.id=c.id RETURNING d.*
        ) SELECT d.id,d.quarantine_id,d.version,d.topic,q.message_key,q.raw_body,
            q.source_topic,q.source_partition,q.source_offset
          FROM claimed d JOIN settlement.inbox_quarantines q ON q.id=d.quarantine_id
        """,
        params,
        (rs, row) ->
            new Delivery(
                rs.getObject("id", UUID.class),
                rs.getObject("quarantine_id", UUID.class),
                rs.getLong("version"),
                rs.getString("topic"),
                keyString(rs.getBytes("message_key")),
                rs.getBytes("raw_body"),
                rs.getString("source_topic"),
                rs.getInt("source_partition"),
                rs.getLong("source_offset")));
  }

  @Override
  public boolean published(Delivery delivery, Instant now) {
    return finish(delivery, now, false);
  }

  @Override
  public boolean failed(Delivery delivery, Instant now) {
    return finish(delivery, now, true);
  }

  private boolean finish(Delivery delivery, Instant now, boolean retry) {
    applyTransactionLimits();
    var params = params(now).addValue("id", delivery.id()).addValue("version", delivery.version());
    // 잠금 대기 중 임대가 끝날 수 있다. 잠금을 먼저 얻은 뒤 DB의 현재 시각으로 판정한다.
    jdbc.queryForList(
        "SELECT id FROM settlement.inbox_quarantine_deliveries WHERE id=:id FOR UPDATE", params);
    return jdbc.update(
            """
            UPDATE settlement.inbox_quarantine_deliveries
            SET status=:status,lease_until=NULL,updated_at=clock_timestamp(),version=version+1,
              next_run_at=clock_timestamp()+(least(60,power(2,least(attempts-1,6))) * interval '1 second')
            WHERE id=:id AND version=:version AND status='RUNNING' AND lease_until>clock_timestamp()
            """,
            params.addValue("status", retry ? "RETRY" : "PUBLISHED"))
        == 1;
  }

  @Override
  public PageView<QuarantineView> findPage(String deliveryStatus, int page, int size) {
    applyTransactionLimits();
    var params =
        new MapSqlParameterSource()
            .addValue("status", deliveryStatus, Types.VARCHAR)
            .addValue("size", size)
            .addValue("offset", (long) page * size);
    String filter =
        """
        WHERE CAST(:status AS varchar) IS NULL OR EXISTS (
          SELECT 1 FROM settlement.inbox_quarantine_deliveries d
          WHERE d.quarantine_id=q.id AND d.status=:status)
        """;
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.inbox_quarantines q " + filter, params, Long.class);
    List<QuarantineRow> rows =
        jdbc.query(
            QUARANTINE_COLUMNS
                + filter
                + " ORDER BY q.created_at DESC, q.id DESC LIMIT :size OFFSET :offset",
            params,
            JdbcQuarantineStore::quarantineRow);
    return new PageView<>(withDeliveries(rows), page, size, total == null ? 0 : total);
  }

  @Override
  public Optional<QuarantineView> find(UUID quarantineId) {
    applyTransactionLimits();
    List<QuarantineRow> rows =
        jdbc.query(
            QUARANTINE_COLUMNS + " WHERE q.id=:id",
            new MapSqlParameterSource("id", quarantineId),
            JdbcQuarantineStore::quarantineRow);
    return withDeliveries(rows).stream().findFirst();
  }

  @Override
  public Optional<DeliveryView> findDelivery(UUID deliveryId) {
    applyTransactionLimits();
    return deliveries("d.id=:id", new MapSqlParameterSource("id", deliveryId)).stream().findFirst();
  }

  /** 원문·키 bytes는 읽지 않고 크기와 해시만 DB에서 계산한다. */
  private static final String QUARANTINE_COLUMNS =
      """
      SELECT q.id,q.consumer,q.source_topic,q.source_partition,q.source_offset,q.error_code,
        octet_length(q.raw_body) AS body_size,encode(sha256(q.raw_body),'hex') AS body_sha256,
        q.version,q.created_at
      FROM settlement.inbox_quarantines q
      """;

  private List<QuarantineView> withDeliveries(List<QuarantineRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<DeliveryView>> byQuarantine =
        deliveries(
                "d.quarantine_id IN (:ids)",
                new MapSqlParameterSource("ids", rows.stream().map(QuarantineRow::id).toList()))
            .stream()
            .collect(Collectors.groupingBy(DeliveryView::quarantineId));
    return rows.stream()
        .map(row -> row.view(byQuarantine.getOrDefault(row.id(), List.of())))
        .toList();
  }

  private List<DeliveryView> deliveries(String where, MapSqlParameterSource params) {
    List<DeliveryRow> rows =
        jdbc.query(
            """
            SELECT d.id,d.quarantine_id,d.kind,d.topic,d.status,d.attempts,d.next_run_at,
              d.lease_until,d.operator_id,d.reason_code,d.version,d.created_at,d.updated_at
            FROM settlement.inbox_quarantine_deliveries d
            """
                + " WHERE "
                + where
                + " ORDER BY d.created_at, d.id",
            params,
            (rs, row) ->
                new DeliveryRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("quarantine_id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("topic"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    instant(rs, "next_run_at"),
                    instant(rs, "lease_until"),
                    rs.getString("operator_id"),
                    rs.getString("reason_code"),
                    rs.getLong("version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at")));
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<ResumeView>> resumes =
        jdbc
            .query(
                """
                SELECT id,delivery_id,expected_version,previous_attempts,operator_id,reason_code,
                  created_at
                FROM settlement.inbox_quarantine_resumes WHERE delivery_id IN (:ids)
                ORDER BY created_at, id
                """,
                new MapSqlParameterSource("ids", rows.stream().map(DeliveryRow::id).toList()),
                (rs, row) ->
                    Map.entry(
                        rs.getObject("delivery_id", UUID.class),
                        new ResumeView(
                            rs.getObject("id", UUID.class),
                            rs.getLong("expected_version"),
                            rs.getInt("previous_attempts"),
                            rs.getString("operator_id"),
                            rs.getString("reason_code"),
                            instant(rs, "created_at"))))
            .stream()
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getKey,
                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    return rows.stream().map(row -> row.view(resumes.getOrDefault(row.id(), List.of()))).toList();
  }

  private static QuarantineRow quarantineRow(ResultSet rs, int row) throws SQLException {
    return new QuarantineRow(
        rs.getObject("id", UUID.class),
        rs.getString("consumer"),
        rs.getString("source_topic"),
        rs.getInt("source_partition"),
        rs.getLong("source_offset"),
        rs.getString("error_code"),
        rs.getObject("body_size", Integer.class),
        rs.getString("body_sha256"),
        rs.getLong("version"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static byte[] keyBytes(String key) {
    return key == null ? null : key.getBytes(StandardCharsets.UTF_8);
  }

  private static String keyString(byte[] key) {
    return key == null ? null : new String(key, StandardCharsets.UTF_8);
  }

  private static MapSqlParameterSource params(Instant now) {
    return new MapSqlParameterSource("now", now.atOffset(ZoneOffset.UTC));
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

  private record Original(UUID id, String key, byte[] body) {}

  private record Exhausted(int attempts, long version, String status) {}

  private record QuarantineRow(
      UUID id,
      String consumer,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      String errorCode,
      Integer bodySize,
      String bodySha256,
      long version,
      Instant createdAt) {
    QuarantineView view(List<DeliveryView> deliveries) {
      return new QuarantineView(
          id,
          consumer,
          sourceTopic,
          sourcePartition,
          sourceOffset,
          errorCode,
          bodySize,
          bodySha256,
          version,
          createdAt,
          deliveries);
    }
  }

  private record DeliveryRow(
      UUID id,
      UUID quarantineId,
      String kind,
      String topic,
      String status,
      int attempts,
      Instant nextRunAt,
      Instant leaseUntil,
      String operatorId,
      String reasonCode,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    DeliveryView view(List<ResumeView> resumes) {
      return new DeliveryView(
          id,
          quarantineId,
          kind,
          topic,
          status,
          attempts,
          nextRunAt,
          leaseUntil,
          operatorId,
          reasonCode,
          version,
          createdAt,
          updatedAt,
          resumes);
    }
  }
}
