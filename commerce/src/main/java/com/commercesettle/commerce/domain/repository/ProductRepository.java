package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.Product;
import java.util.Optional;
import java.util.UUID;

/** 목록은 createdAt DESC, id DESC 순서이며 논리삭제 상품을 제외한다. */
public interface ProductRepository {
  /** 변경을 즉시 DB에 반영해 버전을 갱신하고 동시 변경 충돌을 드러낸다. */
  Product save(Product product);

  Optional<Product> findById(UUID productId);

  PageResult<Product> findBySeller(UUID sellerId, int page, int size);

  PageResult<Product> findOnSale(int page, int size);
}
