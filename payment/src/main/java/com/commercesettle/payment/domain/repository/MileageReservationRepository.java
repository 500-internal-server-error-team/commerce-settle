package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.MileageReservation;
import java.util.Optional;
import java.util.UUID;

public interface MileageReservationRepository {
  MileageReservation save(MileageReservation reservation);

  Optional<MileageReservation> findByPaymentId(UUID paymentId);
}
