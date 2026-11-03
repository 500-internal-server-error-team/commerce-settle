package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
class JpaPayoutRepository implements PayoutRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataPayoutRepository repository;

  JpaPayoutRepository(SpringDataPayoutRepository repository) {
    this.repository = repository;
  }

  @Override
  public Payout save(Payout payout) {
    return repository.save(payout);
  }

  @Override
  public Optional<Payout> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public Optional<Payout> findByBatchId(UUID batchId) {
    return repository.findByBatchId(batchId);
  }

  @Override
  public Optional<Payout> findByIdAndSellerId(UUID id, UUID sellerId) {
    return repository.findByIdAndSellerId(id, sellerId);
  }

  @Override
  public List<Payout> findBySeller(UUID sellerId, int page, int size) {
    return repository.findBySellerId(sellerId, PageRequest.of(page, size, NEWEST_FIRST));
  }

  @Override
  public long countBySeller(UUID sellerId) {
    return repository.countBySellerId(sellerId);
  }
}
