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

  @Query(
      value =
          "SELECT o.* FROM commerce.orders o WHERE o.seller_id = :sellerId ORDER BY o.created_at DESC, o.id DESC LIMIT :pageSize OFFSET :rowOffset",
      nativeQuery = true)
  List<Order> findBySellerWithOffset(
      @Param("sellerId") UUID sellerId,
      @Param("rowOffset") long offset,
      @Param("pageSize") int size);

  long countBySellerId(UUID sellerId);

  /**
   * 세 기한 조건은 서로 겹치지 않는 상태라 조건별로 전용 인덱스를 쓰고 UNION ALL로 합친다. OR 한 조건으로 쓰면 id 순서를 위해 기본 키를 전부 훑으며 완료
   * 주문까지 걸러낸다.
   */
  @Query(
      value =
          """
          SELECT id FROM (
              SELECT id FROM commerce.orders
              WHERE status IN ('PAYMENT_PENDING','DECLINED') AND payment_wait_until <= :now
                AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
              UNION ALL
              SELECT id FROM commerce.orders
              WHERE fulfillment_status = 'SHIPPED' AND status IN ('PAID','REFUND_PENDING')
                AND delivery_due_at <= :now
                AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
              UNION ALL
              SELECT id FROM commerce.orders
              WHERE status = 'PAID' AND fulfillment_status = 'DELIVERED' AND refund_deadline_at < :now
                AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
          ) due
          ORDER BY id LIMIT :limit
          """,
      nativeQuery = true)
  List<UUID> findDue(
      @Param("now") java.time.Instant now, @Param("limit") int limit, @Param("after") UUID after);
}
