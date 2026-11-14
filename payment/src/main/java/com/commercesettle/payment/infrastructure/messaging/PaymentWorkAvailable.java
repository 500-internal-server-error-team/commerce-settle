package com.commercesettle.payment.infrastructure.messaging;

/** 트랜잭션 안에서 발행하는 로컬 깨움 힌트. 실행할 작업의 원본은 DB에 있다. */
public record PaymentWorkAvailable(Kind kind) {
  public enum Kind {
    REQUEST,
    APPROVAL
  }
}
