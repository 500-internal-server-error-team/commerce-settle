package com.commercesettle.commerce.application.exception;

/** 로그인·복구 요청 제한 초과. retryAfterSeconds는 남은 시간창을 초 단위로 올림한 양의 정수다. */
public class AuthRateLimitedException extends RuntimeException {
  private final long retryAfterSeconds;

  public AuthRateLimitedException(long retryAfterSeconds) {
    super("Auth rate limit exceeded");
    if (retryAfterSeconds < 1) {
      throw new IllegalArgumentException("retryAfterSeconds must be positive");
    }
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
