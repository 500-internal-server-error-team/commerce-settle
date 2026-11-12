package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.ProductInventory;
import java.util.Optional;
import java.util.UUID;

public interface ProductInventoryRepository {
  /** 변경을 즉시 DB에 반영해 버전을 갱신하고 동시 변경 충돌을 드러낸다. */
  ProductInventory save(ProductInventory inventory);

  Optional<ProductInventory> findByProductId(UUID productId);

  /** 수량을 바꾸는 모든 흐름이 같은 재고 행 잠금을 거치도록 쓰기 잠금으로 조회한다. */
  Optional<ProductInventory> findByProductIdForUpdate(UUID productId);
}
