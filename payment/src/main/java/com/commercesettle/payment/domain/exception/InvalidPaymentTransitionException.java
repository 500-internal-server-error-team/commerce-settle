package com.commercesettle.payment.domain.exception;

import com.commercesettle.payment.domain.model.PaymentStatus;

/** 결제 상태 전이 규칙이 허용하지 않는 상태 변경을 시도할 때 던진다. */
public class InvalidPaymentTransitionException extends RuntimeException {
  public InvalidPaymentTransitionException(PaymentStatus from, PaymentStatus to) {
    super("결제 상태를 " + from + "에서 " + to + "(으)로 바꿀 수 없습니다.");
  }
}
