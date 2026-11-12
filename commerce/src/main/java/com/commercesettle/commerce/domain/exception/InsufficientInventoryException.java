package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

/** 줄이거나 예약·배정하려는 수량이 일반 가용 재고보다 많다. */
public class InsufficientInventoryException extends RuntimeException {
  public InsufficientInventoryException(UUID productId) {
    super("insufficient available inventory: " + productId);
  }
}
