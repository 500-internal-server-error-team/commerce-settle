package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.BuyerCoupon;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 목록은 createdAt DESC, id DESC 순서다. */
public interface BuyerCouponRepository {
  /** 변경을 즉시 DB에 반영해 계정당 웰컴 1장 같은 유일 제약을 드러낸다. */
  BuyerCoupon save(BuyerCoupon coupon);

  /** 예약·사용·복구 전에 쿠폰 행을 쓰기 잠금으로 읽는다. 같은 쿠폰을 쓰는 주문은 이 잠금으로 직렬화된다. */
  Optional<BuyerCoupon> findByIdForUpdate(UUID couponId);

  Optional<BuyerCoupon> findById(UUID couponId);

  Optional<BuyerCoupon> findWelcome(UUID buyerId);

  boolean existsByTemplateAndBuyer(UUID templateId, UUID buyerId);

  PageResult<BuyerCoupon> findByBuyer(UUID buyerId, int page, int size);

  /** 기한이 지난 미사용(ISSUED) 쿠폰 ID. 기한이 이른 순서다. */
  List<UUID> findExpiredIssuedIds(Instant now, int limit);
}
