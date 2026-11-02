package com.commercesettle.commerce.application.exception;

public class OrderDependencyUnavailableException extends RuntimeException {
  public OrderDependencyUnavailableException() {
    super("Order dependency is not connected");
  }
}
