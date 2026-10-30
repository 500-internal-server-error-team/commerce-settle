package com.commercesettle.settlement.domain.exception;

/** 지급·정산 배치의 상태 전이 규칙이 허용하지 않는 변경을 시도할 때 던진다. */
public class InvalidPayoutTransitionException extends RuntimeException {
  public InvalidPayoutTransitionException(String target, Enum<?> from, Enum<?> to) {
    super(target + " 상태를 " + from + "에서 " + to + "(으)로 바꿀 수 없습니다.");
  }
}
