package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.port.AuditLog;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuditLog implements AuditLog {
  private final NamedParameterJdbcOperations jdbc;

  JdbcAuditLog(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(Entry entry) {
    jdbc.update(
        """
        INSERT INTO settlement.audit_logs (id, actor_kind, actor_ref, action, aggregate_type,
            aggregate_id, before_status, after_status, reason_code, correlation_id, occurred_at)
        VALUES (:id, :actorKind, :actorRef, :action, :aggregateType, :aggregateId, :beforeStatus,
            :afterStatus, :reasonCode, :correlationId, :occurredAt)
        """,
        new MapSqlParameterSource("id", UUID.randomUUID())
            .addValue("actorKind", entry.actorKind())
            .addValue("actorRef", entry.actorRef())
            .addValue("action", entry.action().name())
            .addValue("aggregateType", entry.aggregateType())
            .addValue("aggregateId", entry.aggregateId())
            .addValue("beforeStatus", entry.beforeStatus())
            .addValue("afterStatus", entry.afterStatus())
            .addValue("reasonCode", entry.reasonCode())
            .addValue("correlationId", entry.correlationId())
            .addValue("occurredAt", entry.occurredAt().atOffset(ZoneOffset.UTC)));
  }
}
