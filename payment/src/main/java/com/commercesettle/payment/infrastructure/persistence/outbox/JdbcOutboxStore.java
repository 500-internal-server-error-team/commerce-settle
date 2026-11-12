package com.commercesettle.payment.infrastructure.persistence.outbox;

import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.payment.application.port.OutboxStore;
import com.commercesettle.payment.infrastructure.persistence.TransactionLimits;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 커머스 JdbcOutboxStore와 같은 선점·결과 저장 SQL을 payment.outbox에 실행한다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOutboxStore implements OutboxStore {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOutboxStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    var params =
        new MapSqlParameterSource("limit", limit)
            .addValue("lease", leaseMs)
            .addValue("max", maxAttempts);
    // 첫 상태 조건은 미발행 부분 인덱스의 조건과 같은 모양이어야 한다. 그래야 발행 완료 이력을 훑지 않는다.
    jdbc.update(
        """
        WITH exhausted AS (
            SELECT o.id FROM payment.outbox o
            WHERE o.status IN ('PENDING','RUNNING','RETRY') AND o.attempts >= :max
              AND ((o.status IN ('PENDING','RETRY') AND o.next_run_at <= clock_timestamp())
                OR (o.status='RUNNING' AND o.lease_until <= clock_timestamp()))
            ORDER BY o.sequence_no LIMIT :limit FOR UPDATE OF o SKIP LOCKED
        )
        UPDATE payment.outbox o SET status='REVIEW_REQUIRED',lease_until=NULL,
            updated_at=clock_timestamp(),version=version+1
        FROM exhausted WHERE o.id=exhausted.id
        """,
        params);
    return jdbc.query(
        """
        WITH candidates AS (
            SELECT o.id,o.created_at AS queued_created,o.updated_at AS queued_changed,
                o.next_run_at AS queued_due,o.status AS queued_status,o.lease_until AS queued_lease
            FROM payment.outbox o
            WHERE o.status IN ('PENDING','RUNNING','RETRY') AND o.attempts < :max
              AND ((o.status IN ('PENDING','RETRY') AND o.next_run_at <= clock_timestamp())
                OR (o.status='RUNNING' AND o.lease_until <= clock_timestamp()))
              AND NOT EXISTS (SELECT 1 FROM payment.outbox prior
                WHERE prior.aggregate_id=o.aggregate_id AND prior.status <> 'PUBLISHED'
                  AND (prior.aggregate_version,prior.sequence_no) < (o.aggregate_version,o.sequence_no))
            ORDER BY o.sequence_no LIMIT :limit FOR UPDATE OF o SKIP LOCKED
        )
        UPDATE payment.outbox o SET status='RUNNING',attempts=attempts+1,
            lease_until=clock_timestamp()+(:lease * interval '1 millisecond'),
            updated_at=clock_timestamp(),version=version+1
        FROM candidates WHERE o.id=candidates.id
        RETURNING o.id,o.version,o.topic,o.message_key,o.raw_body,o.trace_parent,o.order_accepted_at,o.attempts,
            candidates.queued_created,candidates.queued_changed,candidates.queued_status,
            CASE WHEN candidates.queued_status='RUNNING' THEN candidates.queued_lease
              ELSE candidates.queued_due END AS queued_eligible
        """,
        params,
        (rs, row) ->
            new Delivery(
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getBytes("raw_body"),
                rs.getString("trace_parent"),
                new Timing(
                    rs.getTimestamp("queued_created").toInstant(),
                    rs.getTimestamp("queued_changed").toInstant(),
                    rs.getTimestamp("queued_eligible").toInstant(),
                    rs.getString("queued_status"),
                    rs.getInt("attempts")),
                rs.getTimestamp("order_accepted_at") == null
                    ? null
                    : rs.getTimestamp("order_accepted_at").toInstant()));
  }

  @Override
  public Set<UUID> markPublished(List<Published> results) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    var params =
        new MapSqlParameterSource("ids", array(results, r -> r.delivery().id()))
            .addValue("versions", array(results, r -> r.delivery().version()))
            .addValue("partitions", array(results, r -> r.publication().partition()))
            .addValue("offsets", array(results, r -> r.publication().offset()));
    // 여러 처리기가 같은 행들을 다른 순서로 잠그지 않게 id 순서로 먼저 잠근다. 잠금 대기 중 임대가 만료될 수 있어
    // 시각 조건은 잠금을 얻은 다음 UPDATE에서 검사한다.
    jdbc.queryForList(
        """
        SELECT o.id FROM payment.outbox o
        JOIN unnest(CAST(:ids AS uuid[]), CAST(:versions AS bigint[])) AS r(id, version)
          ON o.id = r.id AND o.version = r.version
        WHERE o.status='RUNNING'
        ORDER BY o.id FOR UPDATE OF o
        """,
        params);
    return new HashSet<>(
        jdbc.queryForList(
            """
            UPDATE payment.outbox o SET status='PUBLISHED',published_partition=r.partition,
                published_offset=r.position,published_at=clock_timestamp(),lease_until=NULL,
                updated_at=clock_timestamp(),version=o.version+1
            FROM unnest(CAST(:ids AS uuid[]), CAST(:versions AS bigint[]),
                CAST(:partitions AS integer[]), CAST(:offsets AS bigint[]))
                AS r(id, version, partition, position)
            WHERE o.id=r.id AND o.version=r.version AND o.status='RUNNING'
              AND o.lease_until > clock_timestamp()
            RETURNING o.id
            """,
            params,
            UUID.class));
  }

  /** 값 목록을 PostgreSQL 배열 리터럴로 만든다. 숫자와 UUID만 넣으므로 따옴표가 필요 없다. */
  private static String array(List<Published> results, Function<Published, Object> value) {
    return results.stream()
        .map(value)
        .map(String::valueOf)
        .collect(Collectors.joining(",", "{", "}"));
  }

  @Override
  public boolean markFailed(Delivery delivery, int maxAttempts) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    if (!lockCurrentDelivery(delivery)) {
      return false;
    }
    return jdbc.update(
            """
            UPDATE payment.outbox SET status=CASE WHEN attempts >= :max THEN 'REVIEW_REQUIRED' ELSE 'RETRY' END,
                next_run_at=clock_timestamp()+(least(60,power(2,least(attempts-1,6))) * interval '1 second'),
                lease_until=NULL,updated_at=clock_timestamp(),version=version+1
            WHERE id=:id AND version=:version AND status='RUNNING' AND lease_until > clock_timestamp()
            """,
            position(delivery).addValue("max", maxAttempts))
        == 1;
  }

  private boolean lockCurrentDelivery(Delivery delivery) {
    // 잠금 대기 중 임대가 만료될 수 있어 시각 조건은 잠금을 얻은 다음 SQL에서 검사한다.
    return !jdbc.queryForList(
            """
            SELECT id FROM payment.outbox
            WHERE id=:id AND version=:version AND status='RUNNING'
            FOR UPDATE
            """,
            position(delivery))
        .isEmpty();
  }

  private static MapSqlParameterSource position(Delivery delivery) {
    return new MapSqlParameterSource("id", delivery.id()).addValue("version", delivery.version());
  }
}
