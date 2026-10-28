package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
  Payment save(Payment payment);

  Optional<Payment> findById(UUID id);

  Optional<Payment> findByOrderId(UUID orderId);
}
