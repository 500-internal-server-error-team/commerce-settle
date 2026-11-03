package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Payment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPaymentRepository extends JpaRepository<Payment, UUID> {
  Optional<Payment> findByOrderId(UUID orderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.id = :id")
  Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

  Optional<Payment> findByIdAndBuyerId(UUID id, UUID buyerId);

  List<Payment> findByBuyerId(UUID buyerId, Pageable pageable);

  List<Payment> findByBuyerIdAndOrderId(UUID buyerId, UUID orderId, Pageable pageable);

  long countByBuyerId(UUID buyerId);

  long countByBuyerIdAndOrderId(UUID buyerId, UUID orderId);
}
