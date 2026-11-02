package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPaymentRepository extends JpaRepository<Payment, UUID> {
  Optional<Payment> findByOrderId(UUID orderId);

  Optional<Payment> findByIdAndBuyerId(UUID id, UUID buyerId);

  List<Payment> findByBuyerId(UUID buyerId, Pageable pageable);

  List<Payment> findByBuyerIdAndOrderId(UUID buyerId, UUID orderId, Pageable pageable);

  long countByBuyerId(UUID buyerId);

  long countByBuyerIdAndOrderId(UUID buyerId, UUID orderId);
}
