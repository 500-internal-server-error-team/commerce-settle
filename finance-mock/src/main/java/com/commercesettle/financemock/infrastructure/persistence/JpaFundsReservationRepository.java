package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.FundsReservation;
import com.commercesettle.financemock.domain.repository.FundsReservationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaFundsReservationRepository implements FundsReservationRepository {
  private final SpringDataFundsReservationRepository repository;

  JpaFundsReservationRepository(SpringDataFundsReservationRepository repository) {
    this.repository = repository;
  }

  @Override
  public FundsReservation save(FundsReservation reservation) {
    return repository.save(reservation);
  }

  @Override
  public Optional<FundsReservation> findByTransactionId(UUID transactionId) {
    return repository.findByTransactionId(transactionId);
  }
}
