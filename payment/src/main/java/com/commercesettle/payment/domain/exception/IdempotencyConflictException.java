package com.commercesettle.payment.domain.exception;

/** 같은 멱등 키를 다른 요청 본문에 썼다. 응답 code는 COMMON_IDEMPOTENCY_CONFLICT다. */
public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException() {
    super("Idempotency key was used with a different request");
  }
}
