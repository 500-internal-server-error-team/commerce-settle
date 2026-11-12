package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaPaymentRepository implements PaymentRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataPaymentRepository repository;
  private final NamedParameterJdbcTemplate jdbc;

  JpaPaymentRepository(SpringDataPaymentRepository repository, NamedParameterJdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
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
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Payment> findByIdForUpdate(UUID id) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    return repository.findByIdForUpdate(id);
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
}
