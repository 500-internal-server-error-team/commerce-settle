package com.commercesettle.commerce.infrastructure.security;

import com.commercesettle.commerce.application.port.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id. 정책의 시작 설정인 메모리 19MiB·반복 2·병렬 1과 16바이트 salt·32바이트 해시를 쓴다. */
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
