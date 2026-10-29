package com.commercesettle.commerce.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 추가 전용 행위 감사. 업무 변경과 같은 트랜잭션에서만 기록한다. 계약은 docs/data-model.md#audit_logs를 따른다. */
public interface AuditLog {
  void record(Entry entry);

  /**
   * @param actorKind BUYER/SELLER/SERVICE/OPERATOR/SYSTEM
   * @param actorRef 검증한 계정 UUID·서비스/운영자/배치 식별자. 이메일을 넣지 않는다.
   * @param correlationId 비밀값이 아닌 추적 ID. 계정 인증 변경은 가드의 pendingChangeId다.
   */
  record Entry(
      String actorKind,
      String actorRef,
      Action action,
      String aggregateType,
      UUID aggregateId,
      String beforeStatus,
      String afterStatus,
      String reasonCode,
      String correlationId,
      Instant occurredAt) {
    public Entry {
      Objects.requireNonNull(actorKind, "actorKind");
      Objects.requireNonNull(actorRef, "actorRef");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(aggregateType, "aggregateType");
      Objects.requireNonNull(aggregateId, "aggregateId");
      Objects.requireNonNull(correlationId, "correlationId");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  /** 허용한 행위 목록. 새 행위는 감사 계약과 함께 추가한다. */
  enum Action {
    ACCOUNT_AUTH_CHANGED
  }
}
