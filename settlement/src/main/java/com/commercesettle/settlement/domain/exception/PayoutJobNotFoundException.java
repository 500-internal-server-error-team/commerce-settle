package com.commercesettle.settlement.domain.exception;

/** 지급 작업이 없다. */
public class PayoutJobNotFoundException extends RuntimeException {
  public PayoutJobNotFoundException() {
    super("payout job not found");
  }
}
