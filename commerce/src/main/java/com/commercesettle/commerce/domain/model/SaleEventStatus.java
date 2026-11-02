package com.commercesettle.commerce.domain.model;

/** 선착순 이벤트의 저장 상태. SOLD_OUT·CLOSED는 종료 상태이며 다시 열지 않는다. */
public enum SaleEventStatus {
  DRAFT,
  SCHEDULED,
  OPEN,
  SOLD_OUT,
  CLOSED;

  /** 공개 후 종료 전. 이 동안 상품 변경을 막고 배정 재고를 유지한다. */
  public boolean isInProgress() {
    return this == SCHEDULED || this == OPEN;
  }
}
