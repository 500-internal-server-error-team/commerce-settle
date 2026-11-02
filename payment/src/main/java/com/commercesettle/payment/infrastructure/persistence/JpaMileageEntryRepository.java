package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
class JpaMileageEntryRepository implements MileageEntryRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataMileageEntryRepository repository;

  JpaMileageEntryRepository(SpringDataMileageEntryRepository repository) {
    this.repository = repository;
  }

  @Override
  public MileageEntry save(MileageEntry entry) {
    return repository.save(entry);
  }

  @Override
  public List<MileageEntry> findByWalletId(UUID walletId, int page, int size) {
    return repository.findByWalletId(walletId, PageRequest.of(page, size, NEWEST_FIRST));
  }

  @Override
  public long countByWalletId(UUID walletId) {
    return repository.countByWalletId(walletId);
  }
}
