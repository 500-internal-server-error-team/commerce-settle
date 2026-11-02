package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.application.port.OperatorRequestStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOperatorRequestStore implements OperatorRequestStore {
  private final NamedParameterJdbcTemplate jdbc;

  JdbcOperatorRequestStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Completed> find(Scope scope) {
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
