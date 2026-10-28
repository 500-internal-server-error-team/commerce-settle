package com.commercesettle.commerce.application.exception;

/** 같은 멱등키가 다른 본문에 쓰였거나 같은 키의 요청이 동시에 처리되고 있다. */
public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(String message) {
    super(message);
  }
}
