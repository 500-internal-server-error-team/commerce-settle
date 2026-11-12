package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaMileageEntryRepository implements MileageEntryRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataMileageEntryRepository repository;
  @PersistenceContext private EntityManager entityManager;

  JpaMileageEntryRepository(SpringDataMileageEntryRepository repository) {
    this.repository = repository;
  }

  /**
   * 원장은 추가만 하므로 항상 새 행으로 저장한다. 할당 ID에 버전 필드가 없어 save()는 merge로 기존 행을 먼저 조회한다. 같은 업무 키 중복은 기존처럼 유니크
   * 제약으로 거절된다.
   */
  @Override
  @Transactional
  public MileageEntry save(MileageEntry entry) {
    entityManager.persist(entry);
    return entry;
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
