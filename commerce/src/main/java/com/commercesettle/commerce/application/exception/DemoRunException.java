package com.commercesettle.commerce.application.exception;

public class DemoRunException extends RuntimeException {
  private final String code;

  public DemoRunException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
