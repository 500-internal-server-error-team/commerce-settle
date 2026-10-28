package com.commercesettle.payment.domain.exception;

/** 결제 금액이 금액 불변식을 위반할 때 던진다. */
public class InvalidPaymentAmountException extends RuntimeException {
  public InvalidPaymentAmountException(String message) {
    super(message);
  }
}
