package com.commercesettle.commerce.domain.model;

/** 상품 판매 상태. DELETED는 논리삭제로만 도달하며 판매자가 직접 지정하지 않는다. */
public enum ProductStatus {
  DRAFT,
  ON_SALE,
  STOPPED,
  DELETED
}
