package com.commercesettle.commerce.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SpringDataIdempotencyRecordRepository extends JpaRepository<JpaIdempotencyRecord, UUID> {
  Optional<JpaIdempotencyRecord> findByActorKindAndActorIdAndOperationAndKeyHash(
      String actorKind, String actorId, String operation, String keyHash);

  @Modifying(flushAutomatically = true)
  @Query(
      "delete from JpaIdempotencyRecord r where r.actorKind = :actorKind"
          + " and r.actorId = :actorId and r.operation = :operation and r.keyHash = :keyHash")
  void deleteByScope(String actorKind, String actorId, String operation, String keyHash);
}
