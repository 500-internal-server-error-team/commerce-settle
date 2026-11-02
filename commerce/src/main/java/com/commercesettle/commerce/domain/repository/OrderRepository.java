package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 주문과 배송지 사본의 저장 경계. 응용 서비스가 구매자·판매자 소유권을 대조한다. */
public interface OrderRepository {
  /** 호출자의 트랜잭션 안에서 주문·주소를 함께 저장하며 트랜잭션 밖 호출은 거절한다. */
  Order save(Order order, OrderAddress address);

  /** 기존 주문의 변경을 호출자의 트랜잭션에 저장한다. */
  Order save(Order order);

  Optional<Order> findLockedById(UUID orderId);

  Optional<Order> findByPaymentId(UUID paymentId);

  Optional<Order> findByBuyerAndId(UUID buyerId, UUID orderId);

  PageResult<Order> findByBuyer(UUID buyerId, int page, int size);

  Optional<OrderAddress> findAddressByBuyerAndOrderId(UUID buyerId, UUID orderId);

  PageResult<Order> findBySeller(UUID sellerId, int page, int size);

  Optional<Order> findById(UUID orderId);

  /** 제한된 후보를 가져온 뒤 각 주문 잠금 아래 조건을 재검사한다. */
  default List<UUID> findDue(java.time.Instant now, int limit) {
    return findDue(now, limit, null);
  }

  List<UUID> findDue(java.time.Instant now, int limit, UUID after);
}
