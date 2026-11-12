package com.commercesettle.commerce.domain.exception;

import java.util.Objects;

/** 이벤트·상품의 현재 상태나 기간 때문에 요청을 반영할 수 없다. */
public class SaleEventConflictException extends RuntimeException {
  public enum Reason {
    /** 공개한 이벤트는 바꾸거나 다시 공개할 수 없다. */
    NOT_DRAFT,
    /** 공개 후 종료 전 이벤트만 종료할 수 있다. */
    NOT_IN_PROGRESS,
    /** 종료 시각이 지난 이벤트는 공개할 수 없다. */
    PERIOD_ENDED,
    /** 판매 중인 상품만 이벤트로 공개할 수 있다. */
    PRODUCT_NOT_ON_SALE,
    /** 같은 상품에 기간이 겹치는 공개 이벤트가 있다. */
    PERIOD_OVERLAP,
    /** 공개 후 종료 전 이벤트가 있는 상품은 바꾸거나 삭제할 수 없다. */
    PRODUCT_LOCKED_BY_EVENT
  }

  private final Reason reason;

  public SaleEventConflictException(Reason reason) {
    super("sale event conflict: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
