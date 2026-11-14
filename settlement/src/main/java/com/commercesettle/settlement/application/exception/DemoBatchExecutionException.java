package com.commercesettle.settlement.application.exception;

/** 시연 회차는 판매자를 건너뛰지 않고 같은 회차로 재시작한다. */
public class DemoBatchExecutionException extends RuntimeException {
  public DemoBatchExecutionException(RuntimeException cause) {
    super("Demo seller settlement failed", cause);
  }
}
