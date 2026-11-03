package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
class JpaSettlementBatchRepository implements SettlementBatchRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataSettlementBatchRepository repository;

  JpaSettlementBatchRepository(SpringDataSettlementBatchRepository repository) {
    this.repository = repository;
  }

  @Override
  public SettlementBatch save(SettlementBatch batch) {
    return repository.saveAndFlush(batch);
  }

  @Override
  public Optional<SettlementBatch> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public List<SettlementBatch> findAllById(Set<UUID> ids) {
    return repository.findAllById(ids);
  }

  @Override
  public Optional<SettlementBatch> find(UUID sellerId, LocalDate batchDate, String policyVersion) {
    return repository.findBySellerIdAndBatchDateAndPolicyVersion(
        sellerId, batchDate, policyVersion);
  }

  @Override
  public List<SettlementBatch> findBySeller(UUID sellerId, int page, int size) {
    return repository.findBySellerId(sellerId, PageRequest.of(page, size, NEWEST_FIRST));
  }

  @Override
  public long countBySeller(UUID sellerId) {
    return repository.countBySellerId(sellerId);
  }
}
