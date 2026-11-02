package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.BuyerCoupon;
import com.commercesettle.commerce.domain.model.BuyerCouponStatus;
import com.commercesettle.commerce.domain.model.CouponKind;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataBuyerCouponRepository extends JpaRepository<BuyerCoupon, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from BuyerCoupon c where c.id = :couponId")
  Optional<BuyerCoupon> findLockedById(UUID couponId);

  Optional<BuyerCoupon> findByBuyerIdAndKind(UUID buyerId, CouponKind kind);

  Page<BuyerCoupon> findByBuyerId(UUID buyerId, Pageable pageable);

  @Query(
      """
      select c.id from BuyerCoupon c
      where c.status = :status and c.expiresAt <= :now
      order by c.expiresAt, c.id
      """)
  List<UUID> findIdsExpiredBefore(BuyerCouponStatus status, Instant now, Pageable pageable);
}
