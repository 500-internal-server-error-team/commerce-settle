package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.ProductInventory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataProductInventoryRepository extends JpaRepository<ProductInventory, UUID> {
  Optional<ProductInventory> findByProductId(UUID productId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from ProductInventory i where i.productId = :productId")
  Optional<ProductInventory> findLockedByProductId(UUID productId);
}
