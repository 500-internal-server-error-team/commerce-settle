package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 운영 요청의 멱등 기록과 감사 기록. 호출자의 트랜잭션에서 업무 변경과 함께 저장하고 함께 롤백한다. */
public interface OperatorRequestStore {
  /** 같은 실행 주체·작업·키 해시의 요청을 트랜잭션 끝까지 직렬화한 뒤 완료 기록을 읽는다. */
  Optional<Completed> findForUpdate(Scope scope);

  /** 완료 기록을 저장한다. 같은 범위의 기록이 동시에 저장되면 유일성 위반으로 실패한다. */
  void saveCompleted(
      UUID recordId, Scope scope, String requestHash, UUID resourceId, int status, Instant now);

  void audit(Audit audit);

  record Scope(String actorKind, String actorId, String operation, String keyHash) {}

  record Completed(String requestHash, UUID resourceId) {}

  record Audit(
      String actorKind,
      String actorRef,
      String action,
      String aggregateType,
      UUID aggregateId,
      String beforeStatus,
      String afterStatus,
      String reasonCode,
      String correlationId,
      Instant occurredAt) {}
}
