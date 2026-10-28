package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPaymentRepository implements PaymentRepository {
  private final SpringDataPaymentRepository repository;

  JpaPaymentRepository(SpringDataPaymentRepository repository) {
    this.repository = repository;
  }

  @Override
  public Payment save(Payment payment) {
    return repository.save(payment);
  }

  @Override
  public Optional<Payment> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public Optional<Payment> findByOrderId(UUID orderId) {
    return repository.findByOrderId(orderId);
  }
}
