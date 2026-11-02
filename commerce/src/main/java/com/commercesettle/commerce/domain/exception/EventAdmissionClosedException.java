package com.commercesettle.commerce.domain.exception;

import java.util.Objects;

/** 선착순 이벤트에 참여할 수 없다. 이벤트 수량·참여 기록을 바꾸지 않고 거절한다. */
public class EventAdmissionClosedException extends RuntimeException {
  public enum Reason {
    /** 시작 전이거나 종료된 이벤트다. */
    NOT_OPEN,
    /** 남은 수량이 모두 판매·결제 대기 예약이다. */
    NO_REMAINING,
    /** 같은 구매자의 진행 중·확정·환불 참여가 이미 있다. */
    ALREADY_PARTICIPATED
  }

  private final Reason reason;

  public EventAdmissionClosedException(Reason reason) {
    super("event admission closed: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
