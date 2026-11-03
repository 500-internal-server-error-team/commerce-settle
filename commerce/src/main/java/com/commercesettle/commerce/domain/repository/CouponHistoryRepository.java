package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.CouponHistory;

public interface CouponHistoryRepository {
  /** 즉시 저장해 (쿠폰, 작업, 업무 키) 중복을 드러낸다. */
  CouponHistory save(CouponHistory history);
}
