package com.commercesettle.commerce.domain.model;

/** 재고 변경 이력의 작업 종류. */
public enum InventoryOperation {
  SELLER_ADJUSTMENT,
  ORDER_RESERVE,
  ORDER_CONSUME,
  ORDER_RELEASE,
  REFUND_RESTORE,
  RETURN_RESTORE,
  RETURN_DISPOSE,
  EVENT_ALLOCATE,
  EVENT_RETURN
}
