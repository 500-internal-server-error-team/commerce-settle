package com.commercesettle.financemock.application.exception;

/** 요청한 출금 계정이 등록된 PAYOUT 계정이 아니다. */
public class PayoutAccountNotFoundException extends RuntimeException {
  public PayoutAccountNotFoundException(String message) {
    super(message);
  }
}
