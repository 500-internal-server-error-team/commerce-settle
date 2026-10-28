package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPaymentRepository extends JpaRepository<Payment, UUID> {
  Optional<Payment> findByOrderId(UUID orderId);
}
