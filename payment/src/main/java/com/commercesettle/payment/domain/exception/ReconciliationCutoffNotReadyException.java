package com.commercesettle.payment.domain.exception;

/** 마감 시각이 미래이거나 안전 여유가 아직 지나지 않아 기준선을 고정할 수 없다. */
public class ReconciliationCutoffNotReadyException extends RuntimeException {
  public ReconciliationCutoffNotReadyException() {
    super("Reconciliation cutoff is not ready");
  }
}
