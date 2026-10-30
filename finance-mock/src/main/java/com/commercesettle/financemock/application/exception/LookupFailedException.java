package com.commercesettle.financemock.application.exception;

/** 시나리오가 주입한 조회 장애. 저장된 거래 결과와 무관하며 거래 부재로 해석하지 않는다. */
public class LookupFailedException extends RuntimeException {
  public LookupFailedException(String message) {
    super(message);
  }
}
