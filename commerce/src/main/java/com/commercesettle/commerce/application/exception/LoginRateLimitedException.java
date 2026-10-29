package com.commercesettle.commerce.application.exception;

/** 로그인 요청 제한 초과. retryAfterSeconds는 남은 시간창을 초 단위로 올림한 양의 정수다. */
public class LoginRateLimitedException extends RuntimeException {
  private final long retryAfterSeconds;

  public LoginRateLimitedException(long retryAfterSeconds) {
    super("Login rate limit exceeded");
    if (retryAfterSeconds < 1) {
      throw new IllegalArgumentException("retryAfterSeconds must be positive");
    }
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
