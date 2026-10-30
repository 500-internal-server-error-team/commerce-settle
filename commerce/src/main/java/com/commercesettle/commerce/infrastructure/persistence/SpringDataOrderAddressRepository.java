package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.OrderAddress;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SpringDataOrderAddressRepository extends JpaRepository<OrderAddress, UUID> {
  @Query(
      "SELECT a FROM OrderAddress a, CommerceOrder o WHERE a.orderId = o.id"
          + " AND o.buyerId = :buyerId AND o.id = :orderId")
  Optional<OrderAddress> findByBuyerAndOrderId(UUID buyerId, UUID orderId);
}
