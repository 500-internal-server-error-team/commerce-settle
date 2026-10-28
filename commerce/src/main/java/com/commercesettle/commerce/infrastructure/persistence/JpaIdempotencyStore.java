package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.port.IdempotencyStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaIdempotencyStore implements IdempotencyStore {
  private final SpringDataIdempotencyRecordRepository records;

  JpaIdempotencyStore(SpringDataIdempotencyRecordRepository records) {
    this.records = records;
  }

  @Override
  public Optional<CompletedRequest> find(Scope scope) {
    return records
        .findByActorKindAndActorIdAndOperationAndKeyHash(
            scope.actorKind(), scope.actorId(), scope.operation(), scope.keyHash())
        .map(
            record ->
                new CompletedRequest(
                    record.getRequestHash(),
                    record.getResourceId(),
                    record.getResponseStatus(),
                    record.getExpiresAt()));
  }

  @Override
  public void delete(Scope scope) {
    records.deleteByScope(scope.actorKind(), scope.actorId(), scope.operation(), scope.keyHash());
  }

  /** 유니크 제약에서 먼저 막히도록 즉시 flush한다. 같은 키의 다른 트랜잭션이 커밋할 때까지 대기한 뒤 충돌로 끝난다. */
  @Override
  public void saveCompleted(
      UUID recordId,
      Scope scope,
      String requestHash,
      UUID resourceId,
      int responseStatus,
      Instant now,
      Instant expiresAt) {
    try {
      records.saveAndFlush(
          new JpaIdempotencyRecord(
              recordId,
              scope.actorKind(),
              scope.actorId(),
              scope.operation(),
              scope.keyHash(),
              requestHash,
              resourceId,
              responseStatus,
              now.truncatedTo(ChronoUnit.MICROS),
              expiresAt.truncatedTo(ChronoUnit.MICROS)));
    } catch (DataIntegrityViolationException e) {
      throw new IdempotencyConflictException("same key is being processed");
    }
  }
}
