package com.commercesettle.payment.domain.exception;

/** 격리 원문 또는 발행 작업이 없다. */
public class QuarantineNotFoundException extends RuntimeException {
  public QuarantineNotFoundException() {
    super("Quarantine not found");
  }
}
