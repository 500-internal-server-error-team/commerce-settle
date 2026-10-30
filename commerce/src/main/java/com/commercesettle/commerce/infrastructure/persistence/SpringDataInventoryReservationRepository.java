package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.InventoryReservation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SpringDataInventoryReservationRepository
    extends JpaRepository<InventoryReservation, UUID> {
  Optional<InventoryReservation> findByOrderId(UUID orderId);

  @Query("select r.productId from InventoryReservation r where r.orderId = :orderId")
  Optional<UUID> findProductIdByOrderId(UUID orderId);
}
