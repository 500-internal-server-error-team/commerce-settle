package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
  Optional<PaymentAttempt> findByPaymentIdAndCycleId(UUID paymentId, UUID cycleId);
}
