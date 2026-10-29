package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPaymentJobRepository implements PaymentJobRepository {
  private final SpringDataPaymentJobRepository repository;

  JpaPaymentJobRepository(SpringDataPaymentJobRepository repository) {
    this.repository = repository;
  }

  @Override
  public PaymentJob save(PaymentJob job) {
    return repository.save(job);
  }

  @Override
  public Optional<PaymentJob> findByOperationAndBusinessKey(
      PaymentJobOperation operation, UUID businessKey) {
    return repository.findByOperationAndBusinessKey(operation, businessKey);
  }
}
