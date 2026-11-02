package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.BuyerCoupon;
import com.commercesettle.commerce.domain.model.BuyerCouponStatus;
import com.commercesettle.commerce.domain.model.CouponKind;
import com.commercesettle.commerce.domain.repository.BuyerCouponRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaBuyerCouponRepository implements BuyerCouponRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataBuyerCouponRepository coupons;
  private final JdbcOperations jdbc;

  JpaBuyerCouponRepository(SpringDataBuyerCouponRepository coupons, JdbcOperations jdbc) {
    this.coupons = coupons;
    this.jdbc = jdbc;
  }

  /** 같은 구매자의 동시 웰컴 발급이 유일 인덱스를 기다리는 시간도 잠금 대기 상한 안에서 끝낸다. */
  @Override
  public BuyerCoupon save(BuyerCoupon coupon) {
    TransactionLimits.apply(jdbc);
    return coupons.saveAndFlush(coupon);
  }

  @Override
  public Optional<BuyerCoupon> findByIdForUpdate(UUID couponId) {
    TransactionLimits.apply(jdbc);
    return coupons.findLockedById(couponId);
  }

  @Override
  public Optional<BuyerCoupon> findById(UUID couponId) {
    return coupons.findById(couponId);
  }

  @Override
  public Optional<BuyerCoupon> findWelcome(UUID buyerId) {
    return coupons.findByBuyerIdAndKind(buyerId, CouponKind.WELCOME);
  }

  @Override
  public List<UUID> findExpiredIssuedIds(Instant now, int limit) {
    return coupons.findIdsExpiredBefore(BuyerCouponStatus.ISSUED, now, PageRequest.ofSize(limit));
  }

  @Override
  public PageResult<BuyerCoupon> findByBuyer(UUID buyerId, int page, int size) {
    Page<BuyerCoupon> result =
        coupons.findByBuyerId(buyerId, PageRequest.of(page, size, NEWEST_FIRST));
    return new PageResult<>(
        result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
  }
}
