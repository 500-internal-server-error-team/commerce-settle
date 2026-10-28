package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

/** 요청한 감소량이 일반 가용 재고보다 크다. */
public class InsufficientInventoryException extends RuntimeException {
  public InsufficientInventoryException(UUID productId) {
    super("insufficient available inventory: " + productId);
  }
}
