package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderAddress;
import java.util.Optional;
import java.util.UUID;

/** 주문과 배송지 사본의 저장 경계. 조회는 구매자 소유권으로 제한한다. */
public interface OrderRepository {
  /** 호출자의 트랜잭션 안에서 주문·주소를 함께 저장하며 트랜잭션 밖 호출은 거절한다. */
  Order save(Order order, OrderAddress address);

  Optional<Order> findByBuyerAndId(UUID buyerId, UUID orderId);

  PageResult<Order> findByBuyer(UUID buyerId, int page, int size);

  Optional<OrderAddress> findAddressByBuyerAndOrderId(UUID buyerId, UUID orderId);
}
