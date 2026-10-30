package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.InventoryReservation;
import com.commercesettle.commerce.domain.repository.InventoryReservationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaInventoryReservationRepository implements InventoryReservationRepository {
  private final SpringDataInventoryReservationRepository reservations;

  JpaInventoryReservationRepository(SpringDataInventoryReservationRepository reservations) {
    this.reservations = reservations;
  }

  @Override
  public InventoryReservation save(InventoryReservation reservation) {
    return reservations.saveAndFlush(reservation);
  }

  @Override
  public Optional<InventoryReservation> findByOrderId(UUID orderId) {
    return reservations.findByOrderId(orderId);
  }

  @Override
  public Optional<UUID> findProductIdByOrderId(UUID orderId) {
    return reservations.findProductIdByOrderId(orderId);
  }
}
