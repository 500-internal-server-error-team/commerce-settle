package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.application.port.OperatorRequestStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOperatorRequestStore implements OperatorRequestStore {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOperatorRequestStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Completed> findForUpdate(Scope scope) {
    // 완료 기록이 없는 동시 요청도 같은 키로 직렬화한다. 잠금은 호출자의 트랜잭션이 끝날 때 해제한다.
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
    jdbc.queryForList(
        """
        SELECT pg_advisory_xact_lock(hashtextextended(
            jsonb_build_array(CAST(:kind AS text), CAST(:actor AS text),
                CAST(:operation AS text), CAST(:keyHash AS text))::text, 0))
        """,
        scope(scope));
    return jdbc
        .query(
            """
            SELECT request_hash, resource_id FROM payment.idempotency_records
            WHERE actor_kind = :kind AND actor_id = :actor AND operation = :operation
              AND key_hash = :keyHash AND status = 'COMPLETED'
            """,
            scope(scope),
            (rs, row) ->
                new Completed(
                    rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)))
        .stream()
        .findFirst();
  }

  @Override
  public void saveCompleted(
      UUID recordId, Scope scope, String requestHash, UUID resourceId, int status, Instant now) {
    jdbc.update(
        """
        INSERT INTO payment.idempotency_records (id,actor_kind,actor_id,operation,key_hash,
            request_hash,resource_id,response_status,status,created_at,updated_at)
        VALUES (:id,:kind,:actor,:operation,:keyHash,:requestHash,:resource,:status,'COMPLETED',
            :now,:now)
        """,
        scope(scope)
            .addValue("id", recordId)
            .addValue("requestHash", requestHash)
            .addValue("resource", resourceId)
            .addValue("status", status)
            .addValue("now", now.atOffset(ZoneOffset.UTC)));
  }

  @Override
  public void audit(Audit audit) {
    jdbc.update(
        """
        INSERT INTO payment.audit_logs (id,actor_kind,actor_ref,action,aggregate_type,aggregate_id,
            before_status,after_status,reason_code,correlation_id,occurred_at)
        VALUES (:id,:kind,:actor,:action,:type,:aggregate,:before,:after,:reason,:correlation,:at)
        """,
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("kind", audit.actorKind())
            .addValue("actor", audit.actorRef())
            .addValue("action", audit.action())
            .addValue("type", audit.aggregateType())
            .addValue("aggregate", audit.aggregateId())
            .addValue("before", audit.beforeStatus())
            .addValue("after", audit.afterStatus())
            .addValue("reason", audit.reasonCode())
            .addValue("correlation", audit.correlationId())
            .addValue("at", audit.occurredAt().atOffset(ZoneOffset.UTC)));
  }

  private static MapSqlParameterSource scope(Scope scope) {
    return new MapSqlParameterSource()
        .addValue("kind", scope.actorKind())
        .addValue("actor", scope.actorId())
        .addValue("operation", scope.operation())
        .addValue("keyHash", scope.keyHash());
  }
}
