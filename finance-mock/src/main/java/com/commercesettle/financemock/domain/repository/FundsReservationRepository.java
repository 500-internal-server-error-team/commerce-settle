package com.commercesettle.financemock.domain.repository;

import com.commercesettle.financemock.domain.model.FundsReservation;
import java.util.Optional;
import java.util.UUID;

public interface FundsReservationRepository {
  FundsReservation save(FundsReservation reservation);

  Optional<FundsReservation> findByTransactionId(UUID transactionId);
}
