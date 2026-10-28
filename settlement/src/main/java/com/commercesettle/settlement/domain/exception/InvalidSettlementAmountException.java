package com.commercesettle.settlement.domain.exception;

/** 정산 금액이 금액 불변식을 위반할 때 던진다. */
public class InvalidSettlementAmountException extends RuntimeException {
  public InvalidSettlementAmountException(String message) {
    super(message);
  }
}
