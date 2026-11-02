package com.commercesettle.payment.domain.exception;

/** 환불·환불 SAGA의 전이 규칙이 허용하지 않는 변경을 시도할 때 던진다. */
public class InvalidRefundTransitionException extends RuntimeException {
  public InvalidRefundTransitionException(Object from, Object to) {
    super("환불 상태를 " + from + "에서 " + to + "(으)로 바꿀 수 없습니다.");
  }
}
