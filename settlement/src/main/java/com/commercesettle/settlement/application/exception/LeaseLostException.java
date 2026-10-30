package com.commercesettle.settlement.application.exception;

/** 결과를 반영하려는 순간 임대가 끝났거나 다른 실행자에게 넘어갔다. 같은 트랜잭션의 반영을 모두 롤백한다. */
public class LeaseLostException extends RuntimeException {
  public LeaseLostException(String message) {
    super(message);
  }
}
