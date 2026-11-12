package com.commercesettle.settlement.domain.exception;

import com.commercesettle.settlement.domain.model.SettlementItemStatus;

/** 정산 항목 상태 전이 규칙이 허용하지 않는 상태 변경을 시도할 때 던진다. */
public class InvalidSettlementTransitionException extends RuntimeException {
  public InvalidSettlementTransitionException(SettlementItemStatus from, SettlementItemStatus to) {
    super("정산 항목 상태를 " + from + "에서 " + to + "(으)로 바꿀 수 없습니다.");
  }
}
