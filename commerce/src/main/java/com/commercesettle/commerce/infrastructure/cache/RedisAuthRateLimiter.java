package com.commercesettle.commerce.infrastructure.cache;

import com.commercesettle.commerce.application.exception.AuthRateLimitedException;
import com.commercesettle.commerce.application.exception.AuthenticationUnavailableException;
import com.commercesettle.commerce.application.port.AuthRateLimiter;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.infrastructure.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 작업·계정 종류·이메일 HMAC별 고정 시간창 제한. 원문 이메일과 키는 Redis·로그에 남기지 않는다. */
@Component
class RedisAuthRateLimiter implements AuthRateLimiter {
  private static final String HMAC = "HmacSHA256";
  private static final RedisScript<Long> ACQUIRE =
      RedisScript.of(new ClassPathResource("redis/acquire-rate-limit.lua"), Long.class);

  private final StringRedisTemplate redis;
  private final SecretKeySpec key;
  private final AuthProperties.RateLimit limit;

  RedisAuthRateLimiter(StringRedisTemplate redis, AuthProperties properties) {
    this.redis = redis;
    this.limit = properties.rateLimit();
    this.key = new SecretKeySpec(limit.identifierHmacKey().getBytes(StandardCharsets.UTF_8), HMAC);
  }

  @Override
  public void acquire(Operation operation, ActorKind kind, String emailNormalized) {
    Window window =
        switch (operation) {
          case LOGIN -> new Window(limit.loginMaxRequests(), limit.loginWindowSeconds());
          case PASSWORD_RESET_REQUEST ->
              new Window(limit.passwordResetMaxRequests(), limit.passwordResetWindowSeconds());
        };
    Long remainingMillis;
    try {
      remainingMillis =
          redis.execute(
              ACQUIRE,
              List.of(AuthKeys.rateLimit(operation, kind, identifierHash(emailNormalized))),
              Integer.toString(window.maxRequests()),
              Long.toString(window.seconds() * 1000L));
    } catch (DataAccessException e) {
      throw new AuthenticationUnavailableException("Rate limit store is unavailable", e);
    }
    if (remainingMillis == null) {
      throw new AuthenticationUnavailableException("Rate limit was not confirmed");
    }
    if (remainingMillis > 0) {
      throw new AuthRateLimitedException(Math.ceilDiv(remainingMillis, 1000L));
    }
  }

  private String identifierHash(String emailNormalized) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return HexFormat.of()
          .formatHex(mac.doFinal(emailNormalized.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }

  private record Window(int maxRequests, int seconds) {}
}
