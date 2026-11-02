package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.PaymentAttempt;
import com.commercesettle.payment.domain.repository.PaymentAttemptRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
class JpaPaymentAttemptRepository implements PaymentAttemptRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

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

  @Override
  public List<PaymentAttempt> findByPaymentId(UUID paymentId, int page, int size) {
    return repository.findByPaymentId(paymentId, PageRequest.of(page, size, NEWEST_FIRST));
  }

  @Override
  public long countByPaymentId(UUID paymentId) {
    return repository.countByPaymentId(paymentId);
  }
}
