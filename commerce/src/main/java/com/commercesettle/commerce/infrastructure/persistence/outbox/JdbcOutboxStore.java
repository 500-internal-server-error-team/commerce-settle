package com.commercesettle.commerce.infrastructure.persistence.outbox;

import com.commercesettle.commerce.application.port.OutboxStore;
import com.commercesettle.commerce.application.port.OutboxStore.Publication;
import com.commercesettle.commerce.infrastructure.persistence.TransactionLimits;
import com.commercesettle.observability.AsyncObservation.Timing;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOutboxStore implements OutboxStore {
  /**
   * 결과 저장 UPDATE의 id 조건에 쓰는 행 잠금 하위 조회. 잠금을 얻은 뒤에 바깥 UPDATE가 행을 읽으므로, 잠금 대기 중 만료된 임대는 같은 문장의 시각 조건이
   * 거절한다. 대기 중 다른 실행자가 버전을 바꿨으면 하위 조회가 행을 돌려주지 않는다.
   */
  private static final String LOCK_CURRENT =
      "SELECT id FROM commerce.outbox WHERE id=:id AND version=:version AND status='RUNNING'"
          + " FOR UPDATE";

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOutboxStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    var params =
        new MapSqlParameterSource("limit", limit)
            .addValue("lease", leaseMs)
            .addValue("max", maxAttempts);
    // 첫 상태 조건은 미발행 부분 인덱스의 조건과 같은 모양이어야 한다. 그래야 발행 완료 이력을 훑지 않는다.
    // 같은 집계의 미발행 후행이 있으면 결과 저장 직후 다시 선점하도록 successor_waiting으로 알린다.
    return jdbc.query(
        """
        WITH candidates AS (
            SELECT o.id,o.created_at AS queued_created,o.updated_at AS queued_changed,
                o.next_run_at AS queued_due,o.status AS queued_status,o.lease_until AS queued_lease
            FROM commerce.outbox o
            WHERE o.status IN ('PENDING','RUNNING','RETRY') AND o.attempts < :max
              AND ((o.status IN ('PENDING','RETRY') AND o.next_run_at <= clock_timestamp())
                OR (o.status='RUNNING' AND o.lease_until <= clock_timestamp()))
              AND NOT EXISTS (SELECT 1 FROM commerce.outbox prior
                WHERE prior.aggregate_id=o.aggregate_id AND prior.status <> 'PUBLISHED'
                  AND (prior.aggregate_version,prior.sequence_no) < (o.aggregate_version,o.sequence_no))
            ORDER BY o.sequence_no LIMIT :limit FOR UPDATE OF o SKIP LOCKED
        )
        UPDATE commerce.outbox o SET status='RUNNING',attempts=attempts+1,
            lease_until=clock_timestamp()+(:lease * interval '1 millisecond'),
            updated_at=clock_timestamp(),version=version+1
        FROM candidates WHERE o.id=candidates.id
        RETURNING o.id,o.version,o.topic,o.message_key,o.raw_body,o.trace_parent,o.order_accepted_at,o.attempts,
            candidates.queued_created,candidates.queued_changed,candidates.queued_status,
            CASE WHEN candidates.queued_status='RUNNING' THEN candidates.queued_lease
              ELSE candidates.queued_due END AS queued_eligible,
            EXISTS (SELECT 1 FROM commerce.outbox successor
              WHERE successor.aggregate_id=o.aggregate_id AND successor.status <> 'PUBLISHED'
                AND (successor.aggregate_version,successor.sequence_no) > (o.aggregate_version,o.sequence_no))
              AS successor_waiting
        """,
        params,
        (rs, row) ->
            new Delivery(
                rs.getObject("id", java.util.UUID.class),
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
                    : rs.getTimestamp("order_accepted_at").toInstant(),
                rs.getBoolean("successor_waiting")));
  }

  @Override
  public int expireExhausted(int limit, int maxAttempts) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    // 첫 상태 조건은 미발행 부분 인덱스의 조건과 같은 모양이어야 한다. 그래야 발행 완료 이력을 훑지 않는다.
    return jdbc.update(
        """
        WITH exhausted AS (
            SELECT o.id FROM commerce.outbox o
            WHERE o.status IN ('PENDING','RUNNING','RETRY') AND o.attempts >= :max
              AND ((o.status IN ('PENDING','RETRY') AND o.next_run_at <= clock_timestamp())
                OR (o.status='RUNNING' AND o.lease_until <= clock_timestamp()))
            ORDER BY o.sequence_no LIMIT :limit FOR UPDATE OF o SKIP LOCKED
        )
        UPDATE commerce.outbox o SET status='REVIEW_REQUIRED',lease_until=NULL,
            updated_at=clock_timestamp(),version=version+1
        FROM exhausted WHERE o.id=exhausted.id
        """,
        new MapSqlParameterSource("limit", limit).addValue("max", maxAttempts));
  }

  @Override
  public boolean markPublished(Delivery delivery, Publication publication) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    return jdbc.update(
            """
            UPDATE commerce.outbox SET status='PUBLISHED',published_partition=:partition,
                published_offset=:offset,published_at=clock_timestamp(),lease_until=NULL,
                updated_at=clock_timestamp(),version=version+1
            WHERE id=(%s)
              AND version=:version AND status='RUNNING' AND lease_until > clock_timestamp()
            """
                .formatted(LOCK_CURRENT),
            position(delivery)
                .addValue("partition", publication.partition())
                .addValue("offset", publication.offset()))
        == 1;
  }

  @Override
  public boolean markFailed(Delivery delivery, int maxAttempts) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    return jdbc.update(
            """
            UPDATE commerce.outbox SET status=CASE WHEN attempts >= :max THEN 'REVIEW_REQUIRED' ELSE 'RETRY' END,
                next_run_at=clock_timestamp()+(least(60,power(2,least(attempts-1,6))) * interval '1 second'),
                lease_until=NULL,updated_at=clock_timestamp(),version=version+1
            WHERE id=(%s)
              AND version=:version AND status='RUNNING' AND lease_until > clock_timestamp()
            """
                .formatted(LOCK_CURRENT),
            position(delivery).addValue("max", maxAttempts))
        == 1;
  }

  private static MapSqlParameterSource position(Delivery delivery) {
    return new MapSqlParameterSource("id", delivery.id()).addValue("version", delivery.version());
  }
}
