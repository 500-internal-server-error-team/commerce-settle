package com.commercesettle.settlement.application.exception;

/** fixture와 일반 배치의 충돌은 해당 Job을 실패시켜 같은 파라미터로 재시작한다. */
public class FixtureBatchConflictException extends IllegalStateException {
  public FixtureBatchConflictException(String message) {
    super(message);
  }
}
