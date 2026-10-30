package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.Order;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrderRepository extends JpaRepository<Order, UUID> {
  Optional<Order> findByBuyerIdAndId(UUID buyerId, UUID orderId);

  Page<Order> findByBuyerId(UUID buyerId, Pageable pageable);
}
