package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.Order;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataOrderRepository extends JpaRepository<Order, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from CommerceOrder o where o.id = :id")
  Optional<Order> findLockedById(@Param("id") UUID id);

  Optional<Order> findByPaymentId(UUID paymentId);

  Optional<Order> findByBuyerIdAndId(UUID buyerId, UUID orderId);

  Page<Order> findByBuyerId(UUID buyerId, Pageable pageable);

  @Query(
      value =
          """
          SELECT o.* FROM commerce.orders o WHERE o.buyer_id = :buyerId
          ORDER BY o.created_at DESC, o.id DESC
          LIMIT :pageSize OFFSET :rowOffset
          """,
      nativeQuery = true)
  List<Order> findByBuyerWithOffset(
      @Param("buyerId") UUID buyerId,
      @Param("rowOffset") long rowOffset,
      @Param("pageSize") int pageSize);

  long countByBuyerId(UUID buyerId);
}
