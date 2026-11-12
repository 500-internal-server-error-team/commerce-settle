package com.commercesettle.commerce.domain.exception;

public class OrderStateException extends RuntimeException {
  public enum Reason {
    STATE_CONFLICT,
    REFUND_WINDOW_CLOSED,
    REFUND_IN_PROGRESS
  }

  private final Reason reason;

  public OrderStateException(Reason reason) {
    super("Order transition rejected: " + reason);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
