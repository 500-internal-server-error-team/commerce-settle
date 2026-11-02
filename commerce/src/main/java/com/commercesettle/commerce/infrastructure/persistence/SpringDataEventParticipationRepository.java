package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.EventParticipation;
import com.commercesettle.commerce.domain.model.EventParticipationStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataEventParticipationRepository extends JpaRepository<EventParticipation, UUID> {
  Optional<EventParticipation> findByOrderId(UUID orderId);

  boolean existsByEventIdAndBuyerIdAndStatusNot(
      UUID eventId, UUID buyerId, EventParticipationStatus status);
}
