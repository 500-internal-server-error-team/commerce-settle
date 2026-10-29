package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.PasswordResetToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository {
  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  /** 소비 확정 전에 같은 토큰의 동시 사용을 막도록 행을 잠근다. 트랜잭션 안에서만 호출한다. */
  Optional<PasswordResetToken> findLockedByTokenHash(String tokenHash);

  /** 아직 소비·폐기되지 않은 계정의 복구 토큰. 만료된 토큰도 포함한다. */
  List<PasswordResetToken> findOpenByActor(ActorKind kind, UUID actorId);

  PasswordResetToken save(PasswordResetToken token);
}
