package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPayoutRepository implements PayoutRepository {
  private final SpringDataPayoutRepository repository;

  JpaPayoutRepository(SpringDataPayoutRepository repository) {
    this.repository = repository;
  }

  @Override
  public Payout save(Payout payout) {
    return repository.save(payout);
  }

  @Override
  public Optional<Payout> findByBatchId(UUID batchId) {
    return repository.findByBatchId(batchId);
  }
}
