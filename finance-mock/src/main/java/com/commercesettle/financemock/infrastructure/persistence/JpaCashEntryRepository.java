package com.commercesettle.financemock.infrastructure.persistence;

import com.commercesettle.financemock.domain.model.CashEntry;
import com.commercesettle.financemock.domain.repository.CashEntryRepository;
import org.springframework.stereotype.Repository;

@Repository
class JpaCashEntryRepository implements CashEntryRepository {
  private final SpringDataCashEntryRepository repository;

  JpaCashEntryRepository(SpringDataCashEntryRepository repository) {
    this.repository = repository;
  }

  @Override
  public CashEntry save(CashEntry entry) {
    return repository.save(entry);
  }
}
