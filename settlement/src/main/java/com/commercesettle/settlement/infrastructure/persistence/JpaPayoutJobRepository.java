package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPayoutJobRepository implements PayoutJobRepository {
  private final SpringDataPayoutJobRepository repository;

  JpaPayoutJobRepository(SpringDataPayoutJobRepository repository) {
    this.repository = repository;
  }

  @Override
  public PayoutJob save(PayoutJob job) {
    return repository.save(job);
  }

  @Override
  public Optional<PayoutJob> findByPayoutId(UUID payoutId) {
    return repository.findByPayoutId(payoutId);
  }
}
