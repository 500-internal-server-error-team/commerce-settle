package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import org.springframework.stereotype.Repository;

@Repository
class JpaMileageEntryRepository implements MileageEntryRepository {
  private final SpringDataMileageEntryRepository repository;

  JpaMileageEntryRepository(SpringDataMileageEntryRepository repository) {
    this.repository = repository;
  }

  @Override
  public MileageEntry save(MileageEntry entry) {
    return repository.save(entry);
  }
}
