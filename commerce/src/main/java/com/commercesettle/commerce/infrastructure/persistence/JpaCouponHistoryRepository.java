package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponHistory;
import com.commercesettle.commerce.domain.repository.CouponHistoryRepository;
import org.springframework.stereotype.Repository;

@Repository
class JpaCouponHistoryRepository implements CouponHistoryRepository {
  private final SpringDataCouponHistoryRepository histories;

  JpaCouponHistoryRepository(SpringDataCouponHistoryRepository histories) {
    this.histories = histories;
  }

  @Override
  public CouponHistory save(CouponHistory history) {
    return histories.saveAndFlush(history);
  }
}
