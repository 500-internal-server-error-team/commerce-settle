package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAddress;
import com.commercesettle.commerce.domain.repository.OrderRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaOrderRepository implements OrderRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataOrderRepository orders;
  private final SpringDataOrderAddressRepository addresses;
  private final JdbcOperations jdbc;

  JpaOrderRepository(
      SpringDataOrderRepository orders,
      SpringDataOrderAddressRepository addresses,
      JdbcOperations jdbc) {
    this.orders = orders;
    this.addresses = addresses;
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Order save(Order order, OrderAddress address) {
    Objects.requireNonNull(order, "order");
    Objects.requireNonNull(address, "address");
    if (!order.getId().equals(address.getOrderId())) {
      throw new IllegalArgumentException("Order and address snapshot must refer to the same order");
    }
    TransactionLimits.apply(jdbc);
    Order saved = orders.saveAndFlush(order);
    addresses.saveAndFlush(address);
    return saved;
  }

  @Override
  public Optional<Order> findByBuyerAndId(UUID buyerId, UUID orderId) {
    return orders.findByBuyerIdAndId(buyerId, orderId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Order save(Order order) {
    TransactionLimits.apply(jdbc);
    return orders.saveAndFlush(order);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Order> findLockedById(UUID orderId) {
    TransactionLimits.apply(jdbc);
    return orders.findLockedById(orderId);
  }

  @Override
  public Optional<Order> findByPaymentId(UUID paymentId) {
    return orders.findByPaymentId(paymentId);
  }

  @Override
  public PageResult<Order> findByBuyer(UUID buyerId, int page, int size) {
    long offset = (long) page * size;
    if (offset > Integer.MAX_VALUE) {
      return new PageResult<>(
          orders.findByBuyerWithOffset(buyerId, offset, size),
          page,
          size,
          orders.countByBuyerId(buyerId));
    }
    var found = orders.findByBuyerId(buyerId, PageRequest.of(page, size, NEWEST_FIRST));
    return new PageResult<>(
        found.getContent(), found.getNumber(), found.getSize(), found.getTotalElements());
  }

  @Override
  public Optional<OrderAddress> findAddressByBuyerAndOrderId(UUID buyerId, UUID orderId) {
    return addresses.findByBuyerAndOrderId(buyerId, orderId);
  }

  @Override
  public PageResult<Order> findBySeller(UUID sellerId, int page, int size) {
    return new PageResult<>(
        orders.findBySellerWithOffset(sellerId, (long) page * size, size),
        page,
        size,
        orders.countBySellerId(sellerId));
  }

  @Override
  public Optional<Order> findById(UUID id) {
    return orders.findById(id);
  }

  @Override
  public java.util.List<UUID> findDue(java.time.Instant now, int limit, UUID after) {
    return orders.findDue(now, limit, after);
  }
}
