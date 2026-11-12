package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.repository.ProductInventoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaProductInventoryRepository implements ProductInventoryRepository {
  private final SpringDataProductInventoryRepository inventories;
  private final JdbcOperations jdbc;

  JpaProductInventoryRepository(
      SpringDataProductInventoryRepository inventories, JdbcOperations jdbc) {
    this.inventories = inventories;
    this.jdbc = jdbc;
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
    TransactionLimits.apply(jdbc);
    return inventories.findLockedByProductId(productId);
  }
}
