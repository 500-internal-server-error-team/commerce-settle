package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrderRefundRepository extends JpaRepository<OrderRefundRequest, UUID> {
  @org.springframework.data.jpa.repository.Query(
      "select r.orderId from OrderRefundRequest r where r.id = :id")
  Optional<UUID> findOrderId(@org.springframework.data.repository.query.Param("id") UUID id);

  Optional<OrderRefundRequest> findByRefundId(UUID refundId);

  @org.springframework.data.jpa.repository.Query(
      value =
          "SELECT r.* FROM commerce.order_refund_requests r WHERE seller_id=:seller ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset",
      nativeQuery = true)
  java.util.List<OrderRefundRequest> findBySeller(
      @org.springframework.data.repository.query.Param("seller") UUID seller,
      @org.springframework.data.repository.query.Param("offset") long offset,
      @org.springframework.data.repository.query.Param("size") int size);

  long countBySellerId(UUID sellerId);
}
