package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRefundRepository extends JpaRepository<Refund, UUID> {
  @Query(
      value =
          "SELECT r.* FROM payment.refunds r JOIN payment.payments p ON p.id=r.payment_id WHERE p.buyer_id=:buyerId ORDER BY r.created_at DESC,r.id DESC LIMIT :size OFFSET :offset",
      nativeQuery = true)
  java.util.List<Refund> findByBuyer(
      @Param("buyerId") UUID buyerId, @Param("offset") long offset, @Param("size") int size);

  @Query(
      value =
          "SELECT count(*) FROM payment.refunds r JOIN payment.payments p ON p.id=r.payment_id WHERE p.buyer_id=:buyerId",
      nativeQuery = true)
  long countByBuyer(@Param("buyerId") UUID buyerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Refund r where r.id = :id")
  Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

  Optional<Refund> findByOrderRefundRequestId(UUID orderRefundRequestId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Refund r where r.businessKey = :businessKey")
  Optional<Refund> findByBusinessKeyForUpdate(@Param("businessKey") UUID businessKey);

  Optional<Refund> findFirstByPaymentIdAndStatusIn(
      UUID paymentId, Collection<RefundStatus> statuses);
}
