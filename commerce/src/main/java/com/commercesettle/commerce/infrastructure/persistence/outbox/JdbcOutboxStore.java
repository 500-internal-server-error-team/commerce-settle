package com.commercesettle.commerce.infrastructure.persistence.outbox;

import com.commercesettle.commerce.application.port.OutboxStore;
import com.commercesettle.commerce.application.port.OutboxStore.Publication;
import com.commercesettle.commerce.infrastructure.persistence.TransactionLimits;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOutboxStore implements OutboxStore {
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
    jdbc.update(
        """
        WITH exhausted AS (
            SELECT o.id FROM commerce.outbox o
            WHERE o.attempts >= :max
              AND ((o.status IN ('PENDING','RETRY') AND o.next_run_at <= clock_timestamp())
                OR (o.status='RUNNING' AND o.lease_until <= clock_timestamp()))
            ORDER BY o.sequence_no LIMIT :limit FOR UPDATE OF o SKIP LOCKED
        )
        UPDATE commerce.outbox o SET status='REVIEW_REQUIRED',lease_until=NULL,
            updated_at=clock_timestamp(),version=version+1
        FROM exhausted WHERE o.id=exhausted.id
        """,
        params);
    return jdbc.query(
        """
        WITH candidates AS (
            SELECT o.id FROM commerce.outbox o
            WHERE o.attempts < :max
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
        RETURNING o.id,o.version,o.topic,o.message_key,o.raw_body
        """,
        params,
        (rs, row) ->
            new Delivery(
                rs.getObject("id", java.util.UUID.class),
                rs.getLong("version"),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getBytes("raw_body")));
  }

  @Override
  public boolean markPublished(Delivery delivery, Publication publication) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    if (!lockCurrentDelivery(delivery)) {
      return false;
    }
    return jdbc.update(
            """
            UPDATE commerce.outbox SET status='PUBLISHED',published_partition=:partition,
                published_offset=:offset,published_at=clock_timestamp(),lease_until=NULL,
                updated_at=clock_timestamp(),version=version+1
            WHERE id=:id AND version=:version AND status='RUNNING' AND lease_until > clock_timestamp()
            """,
            position(delivery)
                .addValue("partition", publication.partition())
                .addValue("offset", publication.offset()))
        == 1;
  }

  @Override
  public boolean markFailed(Delivery delivery, int maxAttempts) {
    TransactionLimits.apply(jdbc.getJdbcOperations());
    if (!lockCurrentDelivery(delivery)) {
      return false;
    }
    return jdbc.update(
            """
            UPDATE commerce.outbox SET status=CASE WHEN attempts >= :max THEN 'REVIEW_REQUIRED' ELSE 'RETRY' END,
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
            SELECT id FROM commerce.outbox
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
