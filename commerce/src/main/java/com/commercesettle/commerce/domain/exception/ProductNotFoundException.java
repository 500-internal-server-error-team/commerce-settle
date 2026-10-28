package com.commercesettle.commerce.domain.exception;

import java.util.UUID;

/** 상품이 없거나, 삭제됐거나, 요청 주체가 볼 수 없는 상품이다. */
public class ProductNotFoundException extends RuntimeException {
  public ProductNotFoundException(UUID productId) {
    super("product not found: " + productId);
  }
}
