package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.ProductInventory;
import java.util.Optional;
import java.util.UUID;

public interface ProductInventoryRepository {
  /** 변경을 즉시 DB에 반영해 버전을 갱신하고 동시 변경 충돌을 드러낸다. */
  ProductInventory save(ProductInventory inventory);

  Optional<ProductInventory> findByProductId(UUID productId);

  /** 판매 중인 동일 상품 버전의 가용 수량을 잠금 없이 읽는다. */
  Optional<Long> findOrderableAvailableQuantity(UUID productId, long productVersion);

  /** 수량을 바꾸는 모든 흐름이 같은 재고 행 잠금을 거치도록 쓰기 잠금으로 조회한다. */
  Optional<ProductInventory> findByProductIdForUpdate(UUID productId);
}
