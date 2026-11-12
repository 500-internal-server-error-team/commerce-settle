package com.commercesettle.settlement.application.exception;

/** 같은 멱등키를 다른 본문으로 썼거나 같은 키의 요청이 동시에 처리됐다. */
public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(String message) {
    super(message);
  }
}
