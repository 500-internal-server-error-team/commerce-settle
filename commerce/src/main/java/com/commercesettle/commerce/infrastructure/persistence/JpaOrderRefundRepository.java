package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import com.commercesettle.commerce.domain.repository.OrderRefundRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaOrderRefundRepository implements OrderRefundRepository {
  private final SpringDataOrderRefundRepository requests;
  private final JdbcOperations jdbc;

  JpaOrderRefundRepository(SpringDataOrderRefundRepository requests, JdbcOperations jdbc) {
    this.requests = requests;
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderRefundRequest save(OrderRefundRequest r) {
    TransactionLimits.apply(jdbc);
    return requests.saveAndFlush(r);
  }

  public Optional<UUID> findOrderId(UUID id) {
    return requests.findOrderId(id);
  }

  public Optional<OrderRefundRequest> findById(UUID id) {
    return requests.findById(id);
  }

  public Optional<OrderRefundRequest> findByRefundId(UUID id) {
    return requests.findByRefundId(id);
  }

  public PageResult<OrderRefundRequest> findByBuyer(UUID buyer, int page, int size) {
    return new PageResult<>(
        requests.findByBuyer(buyer, (long) page * size, size),
        page,
        size,
        requests.countByBuyerId(buyer));
  }

  public PageResult<OrderRefundRequest> findBySeller(UUID seller, int page, int size) {
    return new PageResult<>(
        requests.findBySeller(seller, (long) page * size, size),
        page,
        size,
        requests.countBySellerId(seller));
  }
}
