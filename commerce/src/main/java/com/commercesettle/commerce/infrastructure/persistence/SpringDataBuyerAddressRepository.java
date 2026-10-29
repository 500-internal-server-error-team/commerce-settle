package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.BuyerAddress;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBuyerAddressRepository extends JpaRepository<BuyerAddress, UUID> {
  Page<BuyerAddress> findByBuyerIdAndDeletedAtIsNull(UUID buyerId, Pageable pageable);

  Optional<BuyerAddress> findByBuyerIdAndDefaultAddressTrueAndDeletedAtIsNull(UUID buyerId);

  boolean existsByBuyerIdAndDeletedAtIsNull(UUID buyerId);
}
