package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponHistory;
import com.commercesettle.commerce.domain.repository.CouponHistoryRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
class JpaCouponHistoryRepository implements CouponHistoryRepository {
  private final EntityManager entityManager;

  JpaCouponHistoryRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** 추가 전용 기록이므로 병합(기존 행 조회) 없이 새로 저장하고 바로 flush해 유일 제약 위반을 드러낸다. */
  @Override
  public CouponHistory save(CouponHistory history) {
    entityManager.persist(history);
    entityManager.flush();
    return history;
  }
}
