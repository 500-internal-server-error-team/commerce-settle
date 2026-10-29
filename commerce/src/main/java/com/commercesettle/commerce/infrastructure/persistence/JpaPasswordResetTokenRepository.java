package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.PasswordResetToken;
import com.commercesettle.commerce.domain.repository.PasswordResetTokenRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPasswordResetTokenRepository implements PasswordResetTokenRepository {
  private final SpringDataPasswordResetTokenRepository tokens;

  JpaPasswordResetTokenRepository(SpringDataPasswordResetTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Override
  public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
    return tokens.findByTokenHash(tokenHash);
  }

  @Override
  public Optional<PasswordResetToken> findLockedByTokenHash(String tokenHash) {
    return tokens.findLockedByTokenHash(tokenHash);
  }

  @Override
  public List<PasswordResetToken> findOpenByActor(ActorKind kind, UUID actorId) {
    return tokens.findByActorKindAndActorIdAndConsumedAtIsNullAndRevokedAtIsNull(kind, actorId);
  }

  @Override
  public PasswordResetToken save(PasswordResetToken token) {
    return tokens.save(token);
  }
}
