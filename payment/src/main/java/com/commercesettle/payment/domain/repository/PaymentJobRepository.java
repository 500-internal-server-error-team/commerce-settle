package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJobRepository {
  PaymentJob save(PaymentJob job);

  Optional<PaymentJob> findByOperationAndBusinessKey(
      PaymentJobOperation operation, UUID businessKey);
}
