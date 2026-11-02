package com.commercesettle.settlement.domain.exception;

/** 지급이 없거나 다른 판매자의 지급이다. 둘을 구분하지 않는다. */
public class PayoutNotFoundException extends RuntimeException {
  public PayoutNotFoundException() {
    super("payout not found");
  }
}
