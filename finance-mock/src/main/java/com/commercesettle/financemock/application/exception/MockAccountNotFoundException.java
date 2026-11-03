package com.commercesettle.financemock.application.exception;

public class MockAccountNotFoundException extends RuntimeException {
  public MockAccountNotFoundException() {
    super("mock account is not found");
  }
}
