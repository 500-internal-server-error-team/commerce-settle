package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageReservation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMileageReservationRepository extends JpaRepository<MileageReservation, UUID> {
  Optional<MileageReservation> findByPaymentId(UUID paymentId);
}
