package com.commercesettle.financemock.domain.exception;

public class InvalidMockTransitionException extends RuntimeException {
  public InvalidMockTransitionException(String message) {
    super(message);
  }
}
