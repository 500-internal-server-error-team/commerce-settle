package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
class JpaPaymentRepository implements PaymentRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

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

  @Override
  public Optional<Payment> findByIdAndBuyerId(UUID id, UUID buyerId) {
    return repository.findByIdAndBuyerId(id, buyerId);
  }

  @Override
  public List<Payment> findByBuyer(UUID buyerId, UUID orderId, int page, int size) {
    PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
    return orderId == null
        ? repository.findByBuyerId(buyerId, request)
        : repository.findByBuyerIdAndOrderId(buyerId, orderId, request);
  }

  @Override
  public long countByBuyer(UUID buyerId, UUID orderId) {
    return orderId == null
        ? repository.countByBuyerId(buyerId)
        : repository.countByBuyerIdAndOrderId(buyerId, orderId);
  }

  @Override
  public void lockOrder(UUID orderId) {
    repository.lockOrder("payment-order:" + orderId);
  }
}
