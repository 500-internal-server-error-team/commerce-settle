package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.PaymentAttempt;
import com.commercesettle.payment.domain.repository.PaymentAttemptRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPaymentAttemptRepository implements PaymentAttemptRepository {
  private final SpringDataPaymentAttemptRepository repository;

  JpaPaymentAttemptRepository(SpringDataPaymentAttemptRepository repository) {
    this.repository = repository;
  }

  @Override
  public PaymentAttempt save(PaymentAttempt attempt) {
    return repository.save(attempt);
  }

  @Override
  public Optional<PaymentAttempt> findByPaymentIdAndCycleId(UUID paymentId, UUID cycleId) {
    return repository.findByPaymentIdAndCycleId(paymentId, cycleId);
  }
}
