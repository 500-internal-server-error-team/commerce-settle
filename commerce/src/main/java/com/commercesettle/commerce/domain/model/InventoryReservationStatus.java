package com.commercesettle.commerce.domain.model;

/**
 * 주문 재고 예약 상태. 기한 경과만으로 RELEASED로 바꾸지 않는다. RESTORED·DISPOSED는 재판매 가능·불가 입고 또는 환불 복구로 끝난 판매이며 주문당
 * 하나만 된다.
 */
public enum InventoryReservationStatus {
  RESERVED,
  CONSUMED,
  RELEASED,
  RESTORED,
  DISPOSED
}
