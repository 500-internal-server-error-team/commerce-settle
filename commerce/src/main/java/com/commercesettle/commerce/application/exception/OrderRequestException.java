package com.commercesettle.commerce.application.exception;

/** 주문 조회·입력 오류. 원문 요청 값은 예외에 포함하지 않고 원인 필드와 안내 문구만 담는다. */
public class OrderRequestException extends RuntimeException {
  public enum Kind {
    NOT_FOUND,
    INVALID_REQUEST
  }

  static final String DEFAULT_REASON = "입력값의 조합을 확인해 주세요.";

  private final Kind kind;
  private final String field;
  private final String reason;

  public OrderRequestException(Kind kind, String field) {
    this(kind, field, DEFAULT_REASON);
  }

  private OrderRequestException(Kind kind, String field, String reason) {
    super("Order request rejected: " + kind);
    this.kind = kind;
    this.field = field;
    this.reason = reason;
  }

  /** 쿠폰을 쓰는 주문은 마일리지를 함께 쓸 수 없다. 쿠폰을 고른 뒤 넣은 마일리지 필드로 알린다. */
  public static OrderRequestException couponWithMileage() {
    return new OrderRequestException(
        Kind.INVALID_REQUEST, "mileageAmount", "쿠폰과 마일리지는 함께 사용할 수 없습니다.");
  }

  public Kind kind() {
    return kind;
  }

  public String field() {
    return field;
  }

  public String reason() {
    return reason;
  }
}
