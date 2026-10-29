package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaSettlementItemRepository implements SettlementItemRepository {
  private final SpringDataSettlementItemRepository repository;

  JpaSettlementItemRepository(SpringDataSettlementItemRepository repository) {
    this.repository = repository;
  }

  @Override
  public SettlementItem save(SettlementItem item) {
    return repository.save(item);
  }

  @Override
  public Optional<SettlementItem> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public Optional<SettlementItem> findByPaymentId(UUID paymentId) {
    return repository.findByPaymentId(paymentId);
  }
}
