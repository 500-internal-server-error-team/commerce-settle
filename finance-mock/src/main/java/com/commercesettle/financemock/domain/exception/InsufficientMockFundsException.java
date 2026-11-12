package com.commercesettle.financemock.domain.exception;

/** 지급 재원이 시험 전제(항상 충분)를 충족하지 못했다. 판매자 지급 상태가 아니라 시험 준비 오류다. */
public class InsufficientMockFundsException extends RuntimeException {
  public InsufficientMockFundsException(String message) {
    super(message);
  }
}
