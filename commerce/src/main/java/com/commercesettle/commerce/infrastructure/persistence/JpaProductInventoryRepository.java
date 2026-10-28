package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.repository.ProductInventoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaProductInventoryRepository implements ProductInventoryRepository {
  private final SpringDataProductInventoryRepository inventories;

  JpaProductInventoryRepository(SpringDataProductInventoryRepository inventories) {
    this.inventories = inventories;
  }

  @Override
  public ProductInventory save(ProductInventory inventory) {
    return inventories.saveAndFlush(inventory);
  }

  @Override
  public Optional<ProductInventory> findByProductId(UUID productId) {
    return inventories.findByProductId(productId);
  }

  @Override
  public Optional<ProductInventory> findByProductIdForUpdate(UUID productId) {
    return inventories.findLockedByProductId(productId);
  }
}
