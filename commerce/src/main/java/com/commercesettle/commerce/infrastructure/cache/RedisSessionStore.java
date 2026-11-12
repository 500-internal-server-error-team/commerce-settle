package com.commercesettle.commerce.infrastructure.cache;

import com.commercesettle.commerce.application.exception.AuthenticationFailedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.port.SessionStore;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 불투명 토큰의 SHA-256 해시로 Redis 세션을 저장한다. 토큰 원문은 저장·기록하지 않는다. */
@Component
class RedisSessionStore implements SessionStore {
  // 발급부터 고정 30분이며 조회로 연장하지 않는다.
  static final Duration LIFETIME = Duration.ofMinutes(30);
  private static final int TOKEN_BYTES = 32;
  private static final String ISSUED = "ISSUED:";
  private static final RedisScript<String> ISSUE =
      RedisScript.of(new ClassPathResource("redis/issue-session.lua"), String.class);
  private static final RedisScript<String> DELETE =
      RedisScript.of(new ClassPathResource("redis/delete-session.lua"), String.class);

  private final StringRedisTemplate redis;
  private final UUID epoch;
  private final SecureRandom random = new SecureRandom();

  RedisSessionStore(StringRedisTemplate redis, AuthProperties properties) {
    this.redis = redis;
    this.epoch = properties.sessionEpoch();
  }

  @Override
  public IssuedSession issue(ActorKind kind, UUID actorId, long authVersion) {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String result =
        run(
            ISSUE,
            List.of(AuthKeys.session(epoch, hash(token)), AuthKeys.subject(epoch, kind, actorId)),
            kind.name(),
            actorId.toString(),
            Long.toString(authVersion),
            Long.toString(LIFETIME.toMillis()));
    if (result != null && result.startsWith(ISSUED)) {
      return new IssuedSession(
          token, Instant.ofEpochMilli(Long.parseLong(result.substring(ISSUED.length()))));
    }
    if ("REJECTED".equals(result)) {
      throw new AuthenticationFailedException();
    }
    throw new AuthenticationUnavailableException("Session was not issued: " + result);
  }

  @Override
  public void delete(String tokenHash, ActorKind kind, UUID actorId) {
    String result =
        run(DELETE, List.of(AuthKeys.session(epoch, tokenHash)), kind.name(), actorId.toString());
    if ("REJECTED".equals(result)) {
      throw new AuthenticationFailedException();
    }
    if (!"DELETED".equals(result)) {
      throw new AuthenticationUnavailableException("Session deletion was not confirmed");
    }
  }

  /** 토큰(UTF-8)의 SHA-256 소문자 16진수. Gateway가 전달하는 세션 해시와 같다. */
  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private String run(RedisScript<String> script, List<String> keys, String... args) {
    try {
      return redis.execute(script, keys, (Object[]) args);
    } catch (DataAccessException e) {
      throw new AuthenticationUnavailableException("Auth store is unavailable", e);
    }
  }
}
