package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponKind;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.model.CouponTemplateStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCouponTemplateRepository extends JpaRepository<CouponTemplate, UUID> {
  Optional<CouponTemplate> findByKindAndStatus(CouponKind kind, CouponTemplateStatus status);

  List<CouponTemplate> findByIdIn(Collection<UUID> ids);
}
