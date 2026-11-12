package com.commercesettle.commerce.domain.exception;

import com.commercesettle.commerce.domain.model.InventoryReservationStatus;
import java.util.UUID;

/** 재고 예약 전이 규칙이 허용하지 않는 변경. 수량·이력을 바꾸지 않고 거절한다. */
public class InvalidReservationTransitionException extends RuntimeException {
  public InvalidReservationTransitionException(
      UUID orderId, InventoryReservationStatus from, InventoryReservationStatus to) {
    super("inventory reservation of order " + orderId + " cannot move from " + from + " to " + to);
  }
}
