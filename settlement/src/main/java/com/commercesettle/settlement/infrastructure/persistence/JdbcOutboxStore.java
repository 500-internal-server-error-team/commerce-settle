package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.settlement.application.port.OutboxStore;
import com.commercesettle.settlement.application.port.OutboxStore.Publication;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 커머스 JdbcOutboxStore와 같은 선점·결과 저장 SQL을 settlement.outbox에 실행한다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcOutboxStore implements OutboxStore {
  private final NamedParameterJdbcTemplate jdbc;

  JdbcOutboxStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int reviewExhausted(int limit, int maxAttempts) {
    applyTransactionLimits();
    // 첫 상태 조건은 미발행 부분 인덱스의 조건과 같은 모양이어야 한다. 그래야 발행 완료 이력을 훑지 않는다.
    return jdbc.update(
        """
        WITH exhausted AS (
            SELECT o.id FROM settlement.outbox o
            WHERE o.status IN ('PENDING','RUNNING','RETRY') AND o.attempts >= :max
              AND ((o.status IN ('PENDING','RETRY') AND o.next_run_at <= clock_timestamp())
                OR (o.status='RUNNING' AND o.lease_until <= clock_timestamp()))
            ORDER BY o.sequence_no LIMIT :limit FOR UPDATE OF o SKIP LOCKED
        )
        UPDATE settlement.outbox o SET status='REVIEW_REQUIRED',lease_until=NULL,
            updated_at=clock_timestamp(),version=version+1
        FROM exhausted WHERE o.id=exhausted.id
        """,
        new MapSqlParameterSource("limit", limit).addValue("max", maxAttempts));
  }

  @Override
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts) {
    applyTransactionLimits();
    var params =
        new MapSqlParameterSource("limit", limit)
            .addValue("lease", leaseMs)
            .addValue("max", maxAttempts);
    // 시도를 다 쓴 행은 attempts 조건으로 거른다. REVIEW_REQUIRED 전환은 복구 주기의 reviewExhausted가 맡는다.
    return jdbc.query(
        """
        WITH candidates AS (
            SELECT o.id,o.created_at AS queued_created,o.updated_at AS queued_changed,
                o.next_run_at AS queued_due,o.status AS queued_status,o.lease_until AS queued_lease
            FROM settlement.outbox o
            WHERE o.status IN ('PENDING','RUNNING','RETRY') AND o.attempts < :max
              AND ((o.status IN ('PENDING','RETRY') AND o.next_run_at <= clock_timestamp())
                OR (o.status='RUNNING' AND o.lease_until <= clock_timestamp()))
              AND NOT EXISTS (SELECT 1 FROM settlement.outbox prior
                WHERE prior.aggregate_id=o.aggregate_id AND prior.status <> 'PUBLISHED'
                  AND (prior.aggregate_version,prior.sequence_no) < (o.aggregate_version,o.sequence_no))
            ORDER BY o.sequence_no LIMIT :limit FOR UPDATE OF o SKIP LOCKED
        )
        UPDATE settlement.outbox o SET status='RUNNING',attempts=attempts+1,
            lease_until=clock_timestamp()+(:lease * interval '1 millisecond'),
            updated_at=clock_timestamp(),version=version+1
        FROM candidates WHERE o.id=candidates.id
        RETURNING o.id,o.version,o.topic,o.message_key,o.raw_body,o.trace_parent,o.attempts,
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
                    rs.getInt("attempts"))));
  }

  // 결과 저장은 UPDATE 한 문장의 영향 행 수로 임대 소유를 판정한다. 잠금을 기다리는 동안 다른 선점이 행을 가져가면 version이
  // 바뀌므로 PostgreSQL이 잠금을 얻은 뒤 최신 행으로 조건(version·임대 시각)을 다시 검사해 0행이 된다.
  @Override
  public PublishResult markPublished(Delivery delivery, Publication publication) {
    applyTransactionLimits();
    List<Boolean> updated =
        jdbc.query(
            """
            UPDATE settlement.outbox o SET status='PUBLISHED',published_partition=:partition,
                published_offset=:offset,published_at=clock_timestamp(),lease_until=NULL,
                updated_at=clock_timestamp(),version=version+1
            WHERE id=:id AND version=:version AND status='RUNNING' AND lease_until > clock_timestamp()
            RETURNING EXISTS (SELECT 1 FROM settlement.outbox later
                WHERE later.aggregate_id=o.aggregate_id AND later.id <> o.id
                  AND later.status IN ('PENDING','RUNNING','RETRY')
                  AND (later.aggregate_version,later.sequence_no) > (o.aggregate_version,o.sequence_no))
            """,
            position(delivery)
                .addValue("partition", publication.partition())
                .addValue("offset", publication.offset()),
            (rs, row) -> rs.getBoolean(1));
    if (updated.isEmpty()) {
      return PublishResult.STALE;
    }
    return updated.getFirst() ? PublishResult.PUBLISHED_WITH_SUCCESSOR : PublishResult.PUBLISHED;
  }

  @Override
  public boolean markFailed(Delivery delivery, int maxAttempts) {
    applyTransactionLimits();
    return jdbc.update(
            """
            UPDATE settlement.outbox SET status=CASE WHEN attempts >= :max THEN 'REVIEW_REQUIRED' ELSE 'RETRY' END,
                next_run_at=clock_timestamp()+(least(60,power(2,least(attempts-1,6))) * interval '1 second'),
                lease_until=NULL,updated_at=clock_timestamp(),version=version+1
            WHERE id=:id AND version=:version AND status='RUNNING' AND lease_until > clock_timestamp()
            """,
            position(delivery).addValue("max", maxAttempts))
        == 1;
  }

  private static MapSqlParameterSource position(Delivery delivery) {
    return new MapSqlParameterSource("id", delivery.id()).addValue("version", delivery.version());
  }

  private void applyTransactionLimits() {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
  }
}
