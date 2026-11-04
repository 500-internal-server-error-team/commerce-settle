package com.commercesettle.payment.infrastructure.persistence.inbox;

import com.commercesettle.payment.application.port.QuarantineStore;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcQuarantineStore implements QuarantineStore {
  /** deliveryStatus가 있으면 그 상태의 DLT/REPLAY 작업을 가진 원문만 고른다. */
  private static final String STATUS_FILTER =
      """
       WHERE CAST(:status AS varchar) IS NULL OR EXISTS (
        SELECT 1 FROM payment.inbox_quarantine_deliveries d
        WHERE d.quarantine_id=q.id AND d.status=CAST(:status AS varchar))
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcQuarantineStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public UUID capture(Source source, String errorCode, Instant now) {
    limits();
    var params =
        params(now)
            .addValue("id", UUID.randomUUID())
            .addValue("consumer", source.consumer())
            .addValue("topic", source.topic())
            .addValue("partition", source.partition())
            .addValue("offset", source.offset())
            .addValue("key", keyBytes(source.key()), Types.BINARY)
            .addValue("body", source.body(), Types.BINARY)
            .addValue("error", errorCode);
    jdbc.update(
        """
        INSERT INTO payment.inbox_quarantines
          (id,consumer,source_topic,source_partition,source_offset,message_key,raw_body,error_code,created_at)
        VALUES (:id,:consumer,:topic,:partition,:offset,:key,:body,:error,:now)
        ON CONFLICT (consumer,source_topic,source_partition,source_offset) DO NOTHING
        """,
        params);
    var original =
        jdbc.queryForObject(
            """
        SELECT id,message_key,raw_body FROM payment.inbox_quarantines
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
        INSERT INTO payment.inbox_quarantine_deliveries
          (id,quarantine_id,kind,topic,status,next_run_at,created_at,updated_at)
        VALUES (:delivery,:id,'DLT','commerce-settle.payment.v1.dlt','PENDING',clock_timestamp(),:now,:now)
        ON CONFLICT (quarantine_id) WHERE kind='DLT' DO NOTHING
        """,
        params);
    return original.id();
  }

  @Override
  public Optional<UUID> requestReplay(
      UUID id, long expectedVersion, String operator, String reasonCode, Instant now) {
    limits();
    var params =
        params(now)
            .addValue("id", id)
            .addValue("version", expectedVersion)
            .addValue("operator", operator)
            .addValue("reason", reasonCode)
            .addValue("delivery", UUID.randomUUID());
    var topics =
        jdbc.query(
            """
        UPDATE payment.inbox_quarantines q SET version=version+1
        WHERE q.id=:id AND q.version=:version
          AND NOT EXISTS (SELECT 1 FROM payment.inbox_quarantine_deliveries d
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
        INSERT INTO payment.inbox_quarantine_deliveries
          (id,quarantine_id,kind,topic,status,operator_id,reason_code,next_run_at,created_at,updated_at)
        VALUES (:delivery,:id,'REPLAY',:topic,'PENDING',:operator,:reason,clock_timestamp(),:now,:now)
        """,
        params);
    return Optional.of((UUID) params.getValue("delivery"));
  }

  @Override
  public Optional<UUID> resumeDelivery(
      UUID id, long expectedVersion, String operator, String reasonCode, Instant now) {
    limits();
    var params =
        params(now)
            .addValue("id", id)
            .addValue("version", expectedVersion)
            .addValue("operator", operator)
            .addValue("reason", reasonCode)
            .addValue("audit", UUID.randomUUID());
    // 재처리 요청과 같은 순서(원문 → 작업)로 잠가, 같은 원문의 새 REPLAY 생성과 재개를 직렬화한다.
    jdbc.queryForList(
        """
        SELECT q.id FROM payment.inbox_quarantines q
        WHERE q.id=(SELECT quarantine_id FROM payment.inbox_quarantine_deliveries WHERE id=:id)
        FOR UPDATE
        """,
        params);
    var rows =
        jdbc.query(
            """
        SELECT d.attempts,d.version,d.status,
          d.kind='REPLAY' AND EXISTS (SELECT 1 FROM payment.inbox_quarantine_deliveries o
            WHERE o.quarantine_id=d.quarantine_id AND o.id<>d.id AND o.kind='REPLAY'
              AND o.status IN ('PENDING','RUNNING','RETRY')) AS other_replay_active
        FROM payment.inbox_quarantine_deliveries d
        WHERE d.id=:id FOR UPDATE OF d
        """,
            params,
            (rs, row) ->
                new Exhausted(
                    rs.getInt("attempts"),
                    rs.getLong("version"),
                    rs.getString("status"),
                    rs.getBoolean("other_replay_active")));
    // 같은 원문의 다른 REPLAY가 발행 중이면 활성 REPLAY 유일성 제약에 걸리기 전에 거절한다.
    if (rows.size() != 1
        || rows.getFirst().version() != expectedVersion
        || !"REVIEW_REQUIRED".equals(rows.getFirst().status())
        || rows.getFirst().otherReplayActive()) {
      return Optional.empty();
    }
    params.addValue("attempts", rows.getFirst().attempts());
    jdbc.update(
        """
        INSERT INTO payment.inbox_quarantine_resumes
          (id,delivery_id,expected_version,previous_attempts,operator_id,reason_code,created_at)
        VALUES (:audit,:id,:version,:attempts,:operator,:reason,:now)
        """,
        params);
    jdbc.update(
        """
        UPDATE payment.inbox_quarantine_deliveries SET status='PENDING',attempts=0,
          next_run_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1
        WHERE id=:id AND version=:version AND status='REVIEW_REQUIRED'
        """,
        params);
    return Optional.of((UUID) params.getValue("audit"));
  }

  @Override
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts, Instant now) {
    limits();
    var params =
        params(now)
            .addValue("limit", limit)
            .addValue("lease", leaseMs)
            .addValue("max", maxAttempts);
    jdbc.update(
        """
        WITH exhausted AS (
          SELECT id FROM payment.inbox_quarantine_deliveries
          WHERE attempts>=:max AND ((status IN ('PENDING','RETRY') AND next_run_at<=clock_timestamp())
            OR (status='RUNNING' AND lease_until<=clock_timestamp()))
          ORDER BY next_run_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
        ) UPDATE payment.inbox_quarantine_deliveries d
        SET status='REVIEW_REQUIRED',lease_until=NULL,updated_at=clock_timestamp(),version=version+1
        FROM exhausted e WHERE d.id=e.id
        """,
        params);
    return jdbc.query(
        """
        WITH candidates AS (
          SELECT id FROM payment.inbox_quarantine_deliveries
          WHERE attempts<:max AND ((status IN ('PENDING','RETRY') AND next_run_at<=clock_timestamp())
            OR (status='RUNNING' AND lease_until<=clock_timestamp()))
          ORDER BY next_run_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
        ), claimed AS (
          UPDATE payment.inbox_quarantine_deliveries d
          SET status='RUNNING',attempts=attempts+1,lease_until=clock_timestamp()+(:lease*interval '1 millisecond'),
            updated_at=clock_timestamp(),version=version+1
          FROM candidates c WHERE d.id=c.id RETURNING d.*
        ) SELECT d.id,d.quarantine_id,d.version,d.kind,d.topic,q.message_key,q.raw_body,
            q.source_topic,q.source_partition,q.source_offset
          FROM claimed d JOIN payment.inbox_quarantines q ON q.id=d.quarantine_id
        """,
        params,
        (rs, row) ->
            new Delivery(
                rs.getObject("id", UUID.class),
                rs.getObject("quarantine_id", UUID.class),
                rs.getLong("version"),
                rs.getString("kind"),
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
    limits();
    var params = params(now).addValue("id", delivery.id()).addValue("version", delivery.version());
    // 잠금 대기 중 임대가 끝날 수 있다. 잠금을 먼저 얻은 뒤 DB의 현재 시각으로 판정한다.
    jdbc.queryForList(
        "SELECT id FROM payment.inbox_quarantine_deliveries WHERE id=:id FOR UPDATE", params);
    return jdbc.update(
            """
        UPDATE payment.inbox_quarantine_deliveries
        SET status=:status,lease_until=NULL,updated_at=clock_timestamp(),version=version+1,
          next_run_at=clock_timestamp()+(least(60,power(2,least(attempts-1,6))) * interval '1 second')
        WHERE id=:id AND version=:version AND status='RUNNING' AND lease_until>clock_timestamp()
        """,
            params.addValue("status", retry ? "RETRY" : "PUBLISHED"))
        == 1;
  }

  private static byte[] keyBytes(String key) {
    return key == null ? null : key.getBytes(StandardCharsets.UTF_8);
  }

  private static String keyString(byte[] key) {
    return key == null ? null : new String(key, StandardCharsets.UTF_8);
  }

  @Override
  public List<QuarantineView> findPage(String deliveryStatus, int page, int size) {
    var ids =
        jdbc.query(
            "SELECT q.id FROM payment.inbox_quarantines q"
                + STATUS_FILTER
                + " ORDER BY q.created_at DESC, q.id DESC LIMIT :size OFFSET :offset",
            new MapSqlParameterSource()
                .addValue("status", deliveryStatus, Types.VARCHAR)
                .addValue("size", size)
                .addValue("offset", (long) page * size),
            (rs, row) -> rs.getObject(1, UUID.class));
    return ids.stream().map(id -> find(id).orElseThrow()).toList();
  }

  @Override
  public long count(String deliveryStatus) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM payment.inbox_quarantines q" + STATUS_FILTER,
            new MapSqlParameterSource().addValue("status", deliveryStatus, Types.VARCHAR),
            Long.class);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<QuarantineView> find(UUID quarantineId) {
    var rows =
        jdbc.query(
            """
            SELECT id,consumer,source_topic,source_partition,source_offset,error_code,
              octet_length(raw_body) AS body_size,encode(sha256(raw_body),'hex') AS body_sha256,
              version,created_at
            FROM payment.inbox_quarantines WHERE id=:id
            """,
            new MapSqlParameterSource("id", quarantineId),
            (rs, row) ->
                new QuarantineView(
                    rs.getObject("id", UUID.class),
                    rs.getString("consumer"),
                    rs.getString("source_topic"),
                    rs.getInt("source_partition"),
                    rs.getLong("source_offset"),
                    rs.getString("error_code"),
                    (Integer) rs.getObject("body_size"),
                    rs.getString("body_sha256"),
                    rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant(),
                    deliveries("d.quarantine_id=:id", quarantineId)));
    return rows.stream().findFirst();
  }

  @Override
  public Optional<DeliveryView> findDelivery(UUID deliveryId) {
    return deliveries("d.id=:id", deliveryId).stream().findFirst();
  }

  /** 원문·키 bytes는 읽지 않는다. 재개 이력은 작업마다 createdAt, id 순서로 붙인다. */
  private List<DeliveryView> deliveries(String where, UUID id) {
    return jdbc.query(
        """
        SELECT d.id,d.quarantine_id,d.kind,d.topic,d.status,d.attempts,d.next_run_at,d.lease_until,
          d.operator_id,d.reason_code,d.version,d.created_at,d.updated_at
        FROM payment.inbox_quarantine_deliveries d WHERE
        """
            + where
            + " ORDER BY d.created_at, d.id",
        new MapSqlParameterSource("id", id),
        (rs, row) -> {
          UUID delivery = rs.getObject("id", UUID.class);
          var leaseUntil = rs.getTimestamp("lease_until");
          return new DeliveryView(
              delivery,
              rs.getObject("quarantine_id", UUID.class),
              rs.getString("kind"),
              rs.getString("topic"),
              rs.getString("status"),
              rs.getInt("attempts"),
              rs.getTimestamp("next_run_at").toInstant(),
              leaseUntil == null ? null : leaseUntil.toInstant(),
              rs.getString("operator_id"),
              rs.getString("reason_code"),
              rs.getLong("version"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant(),
              resumes(delivery));
        });
  }

  private List<ResumeView> resumes(UUID delivery) {
    return jdbc.query(
        """
        SELECT id,expected_version,previous_attempts,operator_id,reason_code,created_at
        FROM payment.inbox_quarantine_resumes WHERE delivery_id=:id ORDER BY created_at, id
        """,
        new MapSqlParameterSource("id", delivery),
        (rs, row) ->
            new ResumeView(
                rs.getObject("id", UUID.class),
                rs.getLong("expected_version"),
                rs.getInt("previous_attempts"),
                rs.getString("operator_id"),
                rs.getString("reason_code"),
                rs.getTimestamp("created_at").toInstant()));
  }

  private static MapSqlParameterSource params(Instant now) {
    return new MapSqlParameterSource("now", now.atOffset(ZoneOffset.UTC));
  }

  private void limits() {
    jdbc.getJdbcTemplate()
        .queryForList(
            """
        SELECT set_config(budget.name,
          least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),budget.timeout_ms)::text,true)
        FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000)) AS budget(name,timeout_ms)
        JOIN pg_settings settings ON settings.name=budget.name
        """);
  }

  private record Original(UUID id, String key, byte[] body) {}

  private record Exhausted(int attempts, long version, String status, boolean otherReplayActive) {}
}
