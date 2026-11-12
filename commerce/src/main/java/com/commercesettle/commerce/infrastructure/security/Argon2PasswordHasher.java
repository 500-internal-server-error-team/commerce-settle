package com.commercesettle.commerce.infrastructure.security;

import com.commercesettle.commerce.application.port.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id. 비용 값을 바꿀 때는 docs/policies.md#비밀번호와-공통-요청과 대상 환경의 로그인 부하를 함께 확인한다. */
@Component
class Argon2PasswordHasher implements PasswordHasher {
  private static final int SALT_BYTES = 16;
  private static final int HASH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19 * 1024;
  private static final int ITERATIONS = 2;

  private final Argon2PasswordEncoder encoder =
      new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);

  @Override
  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  @Override
  public boolean matches(String rawPassword, String passwordHash) {
    return encoder.matches(rawPassword, passwordHash);
  }
}
