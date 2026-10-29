package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.application.exception.IdempotencyConflictException;
import com.commercesettle.commerce.application.port.IdempotencyStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaIdempotencyStore implements IdempotencyStore {
  private final SpringDataIdempotencyRecordRepository records;
  private final JdbcOperations jdbc;

  JpaIdempotencyStore(SpringDataIdempotencyRecordRepository records, JdbcOperations jdbc) {
    this.records = records;
    this.jdbc = jdbc;
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
    TransactionLimits.apply(jdbc);
    records.deleteByScope(scope.actorKind(), scope.actorId(), scope.operation(), scope.keyHash());
  }

  /**
   * 유니크 제약에서 먼저 막히도록 즉시 flush한다. 같은 키의 다른 트랜잭션이 끝나기를 잠금 대기 상한까지 기다리며, 그 트랜잭션이 커밋하거나 상한을 넘으면 처리 중인
   * 같은 키의 충돌로 끝난다.
   */
  @Override
  public void saveCompleted(
      UUID recordId,
      Scope scope,
      String requestHash,
      UUID resourceId,
      int responseStatus,
      Instant now,
      Instant expiresAt) {
    TransactionLimits.apply(jdbc);
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
    } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
      throw new IdempotencyConflictException("same key is being processed");
    }
  }
}
