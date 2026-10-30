package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.Payout;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository {
  Payout save(Payout payout);

  Optional<Payout> findById(UUID id);

  Optional<Payout> findByBatchId(UUID batchId);
}
