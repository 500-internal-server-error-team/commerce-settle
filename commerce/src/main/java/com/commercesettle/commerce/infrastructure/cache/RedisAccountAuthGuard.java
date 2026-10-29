package com.commercesettle.commerce.infrastructure.cache;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.port.AccountAuthGuard;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
class RedisAccountAuthGuard implements AccountAuthGuard {
  private static final RedisScript<String> BLOCK =
      RedisScript.of(new ClassPathResource("redis/block-guard.lua"), String.class);
  private static final RedisScript<String> CONFIRM =
      RedisScript.of(new ClassPathResource("redis/confirm-guard.lua"), String.class);

  private final StringRedisTemplate redis;
  private final UUID epoch;

  RedisAccountAuthGuard(StringRedisTemplate redis, AuthProperties properties) {
    this.redis = redis;
    this.epoch = properties.sessionEpoch();
  }

  @Override
  public void block(ActorKind kind, UUID actorId, UUID changeId, long authVersion) {
    String result = run(BLOCK, kind, actorId, changeId.toString(), Long.toString(authVersion));
    if ("REJECTED".equals(result)) {
      throw new AuthenticationFailedException();
    }
    if (!"BLOCKED".equals(result)) {
      throw new AuthenticationUnavailableException("Auth guard was not blocked: " + result);
    }
  }

  @Override
  public void confirm(
      ActorKind kind, UUID actorId, UUID changeId, long authVersion, boolean active) {
    String result =
        run(
            CONFIRM,
            kind,
            actorId,
            changeId.toString(),
            Long.toString(authVersion),
            active ? "ACTIVE" : "BLOCKED");
    if (!"CONFIRMED".equals(result)) {
      throw new AuthenticationUnavailableException("Auth guard was not confirmed: " + result);
    }
  }

  private String run(RedisScript<String> script, ActorKind kind, UUID actorId, String... args) {
    try {
      return redis.execute(
          script, List.of(AuthKeys.subject(epoch, kind, actorId)), (Object[]) args);
    } catch (DataAccessException e) {
      throw new AuthenticationUnavailableException("Auth store is unavailable", e);
    }
  }
}
