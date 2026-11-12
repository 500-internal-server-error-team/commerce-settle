package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.PaymentAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
  Optional<PaymentAttempt> findByPaymentIdAndCycleId(UUID paymentId, UUID cycleId);

  List<PaymentAttempt> findByPaymentId(UUID paymentId, Pageable pageable);

  long countByPaymentId(UUID paymentId);
}
