package com.commercesettle.commerce.domain.exception;

import java.util.Objects;

/** 월간 회차의 현재 상태·실행·버전·기한 때문에 교정할 수 없다. */
public class CouponRunConflictException extends RuntimeException {
  public enum Reason {
    /** 발급 이력이 있거나 만료·취소됐거나 이미 교정됐다. */
    NOT_REPLACEABLE,
    /** 실행 중이거나 진행 중으로 남은 실행이 있다. */
    EXECUTION_ACTIVE,
    /** 요청한 버전이 현재 버전과 다르다. */
    VERSION_CONFLICT,
    /** 원래 발급 기한이 지났다. */
    EXPIRED
  }

  private final Reason reason;

  public CouponRunConflictException(Reason reason) {
    super("coupon issue run conflict: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
