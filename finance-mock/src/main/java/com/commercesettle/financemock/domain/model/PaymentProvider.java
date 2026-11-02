package com.commercesettle.financemock.domain.model;

public enum PaymentProvider {
  PG_A,
  PG_B,
  PG_C,
  EASY_PAY;

  public void requireMethod(MockPaymentMethod method) {
    if (method == null || (this == EASY_PAY) != (method == MockPaymentMethod.EASY_PAY)) {
      throw new IllegalArgumentException("provider does not support method");
    }
  }
}
