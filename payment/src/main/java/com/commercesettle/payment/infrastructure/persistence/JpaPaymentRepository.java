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
    limits();
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

  /** 결제 행 잠금 전에 현재 트랜잭션의 대기 상한을 건다. 호출자가 정한 더 짧은 상한은 유지하고, 트랜잭션이 끝나면 풀린다. */
  private void limits() {
    jdbc.getJdbcTemplate()
        .queryForList(
            """
            SELECT set_config(budget.name,
                least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),
                    budget.timeout_ms)::text,true)
            FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000))
                AS budget(name,timeout_ms)
            JOIN pg_settings settings ON settings.name=budget.name
            """);
  }
}
