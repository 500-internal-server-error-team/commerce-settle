package com.commercesettle.commerce.infrastructure.cache;

import com.commercesettle.commerce.domain.model.ActorKind;
import java.util.UUID;

/** Redis 인증 키. Gateway의 검증 스크립트와 같은 형식이어야 한다. */
final class AuthKeys {
  private static final String PREFIX = "commerce-settle:auth:";

  private AuthKeys() {}

  static String session(UUID epoch, String tokenHash) {
    return PREFIX + "session:" + epoch + ":" + tokenHash;
  }

  static String subject(UUID epoch, ActorKind kind, UUID actorId) {
    return PREFIX + "subject:" + epoch + ":" + kind.name() + ":" + actorId;
  }

  static String loginRateLimit(ActorKind kind, String identifierHash) {
    return PREFIX + "rate-limit:LOGIN:" + kind.name() + ":" + identifierHash;
  }
}
