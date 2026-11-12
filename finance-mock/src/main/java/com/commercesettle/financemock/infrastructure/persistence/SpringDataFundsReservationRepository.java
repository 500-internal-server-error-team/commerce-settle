package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.FundsReservation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataFundsReservationRepository extends JpaRepository<FundsReservation, UUID> {
  Optional<FundsReservation> findByTransactionId(UUID transactionId);
}
