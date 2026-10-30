package com.commercesettle.financemock.application.exception;

/** 지정한 대역·작업·업무 키의 거래 기록이 조회 시점에 없다. 금융 실패나 취소 확정이 아니다. */
public class TransactionNotFoundException extends RuntimeException {
  public TransactionNotFoundException(String message) {
    super(message);
  }
}
