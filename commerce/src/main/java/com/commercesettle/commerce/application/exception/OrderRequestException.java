package com.commercesettle.commerce.application.exception;

/** 주문 조회·입력 오류. 원문 요청 값은 예외에 포함하지 않는다. */
public class OrderRequestException extends RuntimeException {
  public enum Kind {
    NOT_FOUND,
    INVALID_REQUEST
  }

  private final Kind kind;
  private final String field;

  public OrderRequestException(Kind kind, String field) {
    super("Order request rejected: " + kind);
    this.kind = kind;
    this.field = field;
  }

  public Kind kind() {
    return kind;
  }

  public String field() {
    return field;
  }
}
