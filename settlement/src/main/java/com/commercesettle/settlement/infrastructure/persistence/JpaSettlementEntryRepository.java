package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.domain.model.SettlementEntry;
import com.commercesettle.settlement.domain.repository.SettlementEntryRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 원장은 서버가 ID를 정하고 추가만 하므로 merge(선조회) 대신 persist로 저장한다. */
@Repository
class JpaSettlementEntryRepository implements SettlementEntryRepository {
  private final EntityManager entityManager;

  JpaSettlementEntryRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public void append(List<SettlementEntry> entries) {
    entries.forEach(entityManager::persist);
  }

  @Override
  public List<SettlementEntry> findByPaymentId(UUID paymentId) {
    return entityManager
        .createQuery(
            "SELECT e FROM SettlementEntry e WHERE e.paymentId = :paymentId ORDER BY e.leg",
            SettlementEntry.class)
        .setParameter("paymentId", paymentId)
        .getResultList();
  }
}
