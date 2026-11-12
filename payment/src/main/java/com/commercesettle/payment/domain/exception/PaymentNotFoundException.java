package com.commercesettle.payment.domain.exception;

/** 결제가 없거나 요청한 구매자의 결제가 아니다. 둘을 구분하지 않는다. */
public class PaymentNotFoundException extends RuntimeException {
  public PaymentNotFoundException() {
    super("Payment not found");
  }
}
