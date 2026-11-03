package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.application.exception.IdempotencyConflictException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 계정 종류·계정 ID·작업·키 해시 범위의 요청 멱등 기록. 업무 변경과 같은 트랜잭션에서 사용한다. */
public interface IdempotencyStore {
  Optional<CompletedRequest> find(Scope scope);

  /** 완료 기록을 즉시 DB에 반영한다. 같은 범위의 기록이 먼저 저장됐다면 {@link IdempotencyConflictException}을 던진다. */
  void saveCompleted(
      Scope scope, String requestHash, UUID resourceId, int responseStatus, Instant now);

  record Scope(String actorKind, String actorId, String operation, String keyHash) {}

  record CompletedRequest(String requestHash, UUID resourceId) {}
}
