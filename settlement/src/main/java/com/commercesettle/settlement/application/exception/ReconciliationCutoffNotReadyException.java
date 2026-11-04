package com.commercesettle.settlement.application.exception;

public class ReconciliationCutoffNotReadyException extends RuntimeException {
  public ReconciliationCutoffNotReadyException() {
    super("Reconciliation cutoff is not ready");
  }
}
