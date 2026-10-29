package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataPasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
  Optional<PasswordResetToken> findLockedByTokenHash(String tokenHash);

  List<PasswordResetToken> findByActorKindAndActorIdAndConsumedAtIsNullAndRevokedAtIsNull(
      ActorKind actorKind, UUID actorId);
}
