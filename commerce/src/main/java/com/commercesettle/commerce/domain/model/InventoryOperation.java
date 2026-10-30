package com.commercesettle.commerce.domain.model;

/** 재고 변경 이력의 작업 종류. 이벤트 배정·반환은 선착순 이벤트 흐름을 구현할 때 추가한다. */
public enum InventoryOperation {
  SELLER_ADJUSTMENT,
  ORDER_RESERVE,
  ORDER_CONSUME,
  ORDER_RELEASE,
  REFUND_RESTORE,
  RETURN_RESTORE
}
