package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SpringDataCouponIssueRunRepository extends JpaRepository<CouponIssueRun, UUID> {
  Optional<CouponIssueRun> findByTargetMonthAndStatusNot(
      LocalDate targetMonth, CouponIssueRunStatus status);

  @Query(
      "select r.targetMonth from CouponIssueRun r"
          + " where r.targetMonth < :targetMonth and r.status in :statuses order by r.targetMonth")
  List<LocalDate> findMonthsBefore(
      LocalDate targetMonth, Collection<CouponIssueRunStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from CouponIssueRun r where r.id = :runId")
  Optional<CouponIssueRun> findLockedById(UUID runId);
}
