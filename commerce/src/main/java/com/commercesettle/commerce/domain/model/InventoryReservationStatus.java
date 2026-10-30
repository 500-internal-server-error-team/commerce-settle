package com.commercesettle.commerce.domain.model;

/** 주문 재고 예약 상태. 기한 경과만으로 RELEASED로 바꾸지 않는다. */
public enum InventoryReservationStatus {
  RESERVED,
  CONSUMED,
  RELEASED,
  RESTORED
}
