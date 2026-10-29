package com.commercesettle.commerce.application.exception;

/** 개발용 주문 참조·입력 오류. HTTP 변환은 presentation에서 담당한다. */
public class OrderStubException extends RuntimeException {
  public enum Kind {
    NOT_FOUND,
    INVALID_REQUEST
  }

  private final Kind kind;
  private final String field;

  public OrderStubException(Kind kind, String field) {
    super("Order stub request rejected: " + kind);
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
