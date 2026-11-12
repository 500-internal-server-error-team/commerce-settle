package com.commercesettle.payment.domain.model;

public enum PaymentStatus {
  PENDING,
  PROCESSING,
  CONFIRMED,
  DECLINED,
  UNKNOWN,
  CANCELLED;

  public boolean canTransitionTo(PaymentStatus next) {
    return switch (this) {
      case PENDING -> next == PROCESSING || next == CONFIRMED || next == CANCELLED;
      case PROCESSING -> next == CONFIRMED || next == DECLINED || next == UNKNOWN;
      case UNKNOWN -> next == CONFIRMED || next == DECLINED;
      case DECLINED -> next == PROCESSING || next == CANCELLED;
      case CONFIRMED, CANCELLED -> false;
    };
  }
}
