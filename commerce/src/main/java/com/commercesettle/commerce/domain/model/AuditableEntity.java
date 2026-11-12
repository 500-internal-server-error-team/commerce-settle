package com.commercesettle.commerce.domain.model;

import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 생성 시각은 생성 때 고정하고, 수정 시각은 성공한 상태 변경에만 갱신한다. 시각은 마이크로초로 맞춘다. */
@MappedSuperclass
public abstract class AuditableEntity {
  private Instant createdAt;
  private Instant updatedAt;

  protected AuditableEntity() {}

  protected AuditableEntity(Instant now) {
    Instant time = truncate(now);
    this.createdAt = time;
    this.updatedAt = time;
  }

  protected void touch(Instant now) {
    this.updatedAt = truncate(now);
  }

  protected static Instant truncate(Instant time) {
    return Objects.requireNonNull(time, "time").truncatedTo(ChronoUnit.MICROS);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
