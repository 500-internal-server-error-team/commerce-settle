package com.commercesettle.payment.domain.exception;

/** 결제나 작업의 현재 상태에서 요청을 처리할 수 없다. 응답 code는 PAYMENT_STATE_CONFLICT다. */
public class PaymentStateConflictException extends RuntimeException {
  public PaymentStateConflictException(String message) {
    super(message);
  }
}
