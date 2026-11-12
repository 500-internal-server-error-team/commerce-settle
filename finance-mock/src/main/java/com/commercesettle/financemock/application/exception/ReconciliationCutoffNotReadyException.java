package com.commercesettle.financemock.application.exception;

public class ReconciliationCutoffNotReadyException extends RuntimeException {
  public ReconciliationCutoffNotReadyException() {
    super("Reconciliation cutoff is not ready");
  }
}
