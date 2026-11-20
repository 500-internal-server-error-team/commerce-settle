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

  @Query(
      """
      select i.availableQuantity from ProductInventory i where i.productId = :productId
        and exists (
          select p.id from Product p where p.id = i.productId and p.version = :productVersion
            and p.status = com.commercesettle.commerce.domain.model.ProductStatus.ON_SALE
            and p.deletedAt is null
        )
      """)
  Optional<Long> findOrderableAvailableQuantity(UUID productId, long productVersion);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from ProductInventory i where i.productId = :productId")
  Optional<ProductInventory> findLockedByProductId(UUID productId);
}
