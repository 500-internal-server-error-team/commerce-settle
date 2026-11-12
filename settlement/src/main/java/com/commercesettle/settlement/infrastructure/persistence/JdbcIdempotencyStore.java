package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.exception.IdempotencyConflictException;
import com.commercesettle.settlement.application.port.IdempotencyStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** 운영 명령의 완료 기록. 금융 작업이라 만료 시각을 두지 않는다. */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {
  private final NamedParameterJdbcOperations jdbc;

  JdbcIdempotencyStore(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CompletedRequest> find(Scope scope) {
    return jdbc
        .query(
            """
            SELECT request_hash, resource_id FROM settlement.idempotency_records
            WHERE actor_kind = :actorKind AND actor_id = :actorId AND operation = :operation
              AND key_hash = :keyHash AND status = 'COMPLETED'
            """,
            params(scope),
            (rs, row) ->
                new CompletedRequest(
                    rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)))
        .stream()
        .findFirst();
  }

  @Override
  public void saveCompleted(
      Scope scope, String requestHash, UUID resourceId, int responseStatus, Instant now) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO settlement.idempotency_records (id, actor_kind, actor_id, operation,
                key_hash, request_hash, resource_id, response_status, status, created_at, updated_at)
            VALUES (:id, :actorKind, :actorId, :operation, :keyHash, :requestHash, :resourceId,
                :responseStatus, 'COMPLETED', :now, :now)
            ON CONFLICT (actor_kind, actor_id, operation, key_hash) DO NOTHING
            """,
            params(scope)
                .addValue("id", UUID.randomUUID())
                .addValue("requestHash", requestHash)
                .addValue("resourceId", resourceId)
                .addValue("responseStatus", responseStatus)
                .addValue("now", now.atOffset(ZoneOffset.UTC)));
    if (inserted != 1) {
      // 같은 키의 다른 요청이 먼저 커밋했다. 이번 업무 변경은 호출자의 트랜잭션과 함께 롤백된다.
      throw new IdempotencyConflictException("same key was completed by another request");
    }
  }

  private static MapSqlParameterSource params(Scope scope) {
    return new MapSqlParameterSource("actorKind", scope.actorKind())
        .addValue("actorId", scope.actorId())
        .addValue("operation", scope.operation())
        .addValue("keyHash", scope.keyHash());
  }
}
