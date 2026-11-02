package com.commercesettle.payment.domain.exception;

public class PaymentJobNotFoundException extends RuntimeException {
  public PaymentJobNotFoundException() {
    super("Payment job not found");
  }
}
