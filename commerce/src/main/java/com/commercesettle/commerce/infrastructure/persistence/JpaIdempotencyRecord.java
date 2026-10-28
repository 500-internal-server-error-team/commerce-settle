package com.commercesettle.commerce.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 요청 멱등 기록의 기술용 행. 응답 본문은 보존하지 않고 리소스 ID로 기존 결과를 다시 조회한다. */
@Entity
@Table(name = "idempotency_records")
class JpaIdempotencyRecord {
  static final String COMPLETED = "COMPLETED";

  @Id private UUID id;
  private String actorKind;
  private String actorId;
  private String operation;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 64)
  private String keyHash;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 64)
  private String requestHash;

  private UUID resourceId;
  private Integer responseStatus;
  private String status;
  private Instant expiresAt;
  private Instant createdAt;
  private Instant updatedAt;
  @Version private Long version;

  protected JpaIdempotencyRecord() {}

  JpaIdempotencyRecord(
      UUID id,
      String actorKind,
      String actorId,
      String operation,
      String keyHash,
      String requestHash,
      UUID resourceId,
      int responseStatus,
      Instant now,
      Instant expiresAt) {
    this.id = id;
    this.actorKind = actorKind;
    this.actorId = actorId;
    this.operation = operation;
    this.keyHash = keyHash;
    this.requestHash = requestHash;
    this.resourceId = resourceId;
    this.responseStatus = responseStatus;
    this.status = COMPLETED;
    this.expiresAt = expiresAt;
    this.createdAt = now;
    this.updatedAt = now;
  }

  String getRequestHash() {
    return requestHash;
  }

  UUID getResourceId() {
    return resourceId;
  }

  Integer getResponseStatus() {
    return responseStatus;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }
}
