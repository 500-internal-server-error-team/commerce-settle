package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageReservation;
import com.commercesettle.payment.domain.repository.MileageReservationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaMileageReservationRepository implements MileageReservationRepository {
  private final SpringDataMileageReservationRepository repository;

  JpaMileageReservationRepository(SpringDataMileageReservationRepository repository) {
    this.repository = repository;
  }

  @Override
  public MileageReservation save(MileageReservation reservation) {
    return repository.save(reservation);
  }

  @Override
  public Optional<MileageReservation> findByPaymentId(UUID paymentId) {
    return repository.findByPaymentId(paymentId);
  }
}
