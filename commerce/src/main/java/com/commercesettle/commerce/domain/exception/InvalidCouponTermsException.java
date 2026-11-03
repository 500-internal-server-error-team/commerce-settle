package com.commercesettle.commerce.domain.exception;

import java.util.Objects;

/** 판매자 캠페인 조건이 정책을 지키지 않는다. 입력 필드와 이유를 함께 알린다. */
public class InvalidCouponTermsException extends RuntimeException {
  private final String field;

  public InvalidCouponTermsException(String field, String reason) {
    super(reason);
    this.field = Objects.requireNonNull(field, "field");
  }

  public String field() {
    return field;
  }
}
