package com.commercesettle.financemock.application.exception;

/** 같은 업무 키에 다른 요청이 왔거나 같은 키를 다른 요청이 처리 중이다. */
public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(String message) {
    super(message);
  }
}
