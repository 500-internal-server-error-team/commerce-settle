package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository {
  PaymentAttempt save(PaymentAttempt attempt);

  /** 결제 회차의 승인 시도. 회차마다 하나만 만든다. */
  Optional<PaymentAttempt> findByPaymentIdAndCycleId(UUID paymentId, UUID cycleId);
}
