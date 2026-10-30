package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.PayoutJob;
import java.util.Optional;
import java.util.UUID;

public interface PayoutJobRepository {
  PayoutJob save(PayoutJob job);

  Optional<PayoutJob> findByPayoutId(UUID payoutId);
}
