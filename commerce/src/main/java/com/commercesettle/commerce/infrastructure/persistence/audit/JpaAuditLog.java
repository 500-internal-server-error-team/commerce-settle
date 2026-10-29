package com.commercesettle.commerce.infrastructure.persistence.audit;

import com.commercesettle.commerce.application.port.AuditLog;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 감사 기록의 기술용 행. 업무 집계와 구분하며 갱신하지 않는다. */
@Entity
@Table(name = "audit_logs")
class JpaAuditLog {
  @Id private UUID id;
  private String actorKind;
  private String actorRef;
  private String action;
  private String aggregateType;
  private UUID aggregateId;
  private String beforeStatus;
  private String afterStatus;
  private String reasonCode;
  private String correlationId;
  private Instant occurredAt;

  protected JpaAuditLog() {}

  JpaAuditLog(AuditLog.Entry entry) {
    this.id = UUID.randomUUID();
    this.actorKind = entry.actorKind();
    this.actorRef = entry.actorRef();
    this.action = entry.action().name();
    this.aggregateType = entry.aggregateType();
    this.aggregateId = entry.aggregateId();
    this.beforeStatus = entry.beforeStatus();
    this.afterStatus = entry.afterStatus();
    this.reasonCode = entry.reasonCode();
    this.correlationId = entry.correlationId();
    this.occurredAt = entry.occurredAt().truncatedTo(ChronoUnit.MICROS);
  }
}
