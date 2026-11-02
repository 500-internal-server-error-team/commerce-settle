package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponKind;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.model.CouponTemplateStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataCouponTemplateRepository extends JpaRepository<CouponTemplate, UUID> {
  Optional<CouponTemplate> findByKindAndStatus(CouponKind kind, CouponTemplateStatus status);

  List<CouponTemplate> findByIdIn(Collection<UUID> ids);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from CouponTemplate t where t.id = :templateId")
  Optional<CouponTemplate> findLockedById(UUID templateId);

  Page<CouponTemplate> findByKindAndSellerId(CouponKind kind, UUID sellerId, Pageable pageable);
}
