package com.commercesettle.financemock.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataIdempotencyRecordRepository extends JpaRepository<JpaIdempotencyRecord, UUID> {
  Optional<JpaIdempotencyRecord> findByActorKindAndActorIdAndOperationAndKeyHash(
      String actorKind, String actorId, String operation, String keyHash);
}
