package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaSettlementBatchRepository implements SettlementBatchRepository {
  private final SpringDataSettlementBatchRepository repository;

  JpaSettlementBatchRepository(SpringDataSettlementBatchRepository repository) {
    this.repository = repository;
  }

  @Override
  public SettlementBatch save(SettlementBatch batch) {
    return repository.saveAndFlush(batch);
  }

  @Override
  public Optional<SettlementBatch> find(UUID sellerId, LocalDate batchDate, String policyVersion) {
    return repository.findBySellerIdAndBatchDateAndPolicyVersion(
        sellerId, batchDate, policyVersion);
  }
}
