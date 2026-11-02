package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponIssueTarget;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataCouponIssueTargetRepository extends JpaRepository<CouponIssueTarget, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from CouponIssueTarget t where t.id = :targetId")
  Optional<CouponIssueTarget> findLockedById(UUID targetId);
}
