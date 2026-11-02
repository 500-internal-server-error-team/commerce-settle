package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import java.util.Optional;
import java.util.UUID;

public interface OrderRefundRepository {
  Optional<UUID> findOrderId(UUID id);

  OrderRefundRequest save(OrderRefundRequest request);

  Optional<OrderRefundRequest> findById(UUID id);

  Optional<OrderRefundRequest> findByRefundId(UUID refundId);

  PageResult<OrderRefundRequest> findBySeller(UUID seller, int page, int size);
}
