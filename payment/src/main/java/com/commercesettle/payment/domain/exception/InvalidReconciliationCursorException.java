package com.commercesettle.payment.domain.exception;

/** cursor를 해석할 수 없거나 최초 조회와 다른 cutoffAt·size로 다시 쓰였다. */
public class InvalidReconciliationCursorException extends RuntimeException {
  public InvalidReconciliationCursorException() {
    super("Invalid reconciliation cursor");
  }
}
