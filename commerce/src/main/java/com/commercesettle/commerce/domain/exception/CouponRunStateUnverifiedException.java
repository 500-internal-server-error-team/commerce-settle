package com.commercesettle.commerce.domain.exception;

/** 실행자 부재·저장 정책·발급 이력의 정합성을 확인할 수 없어 교정을 미룬다. 현재 값으로 대체하지 않는다. */
public class CouponRunStateUnverifiedException extends RuntimeException {
  public CouponRunStateUnverifiedException(String detail) {
    super(detail);
  }
}
