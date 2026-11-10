package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageAccrual;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMileageAccrualRepository extends JpaRepository<MileageAccrual, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from MileageAccrual a where a.paymentId = :paymentId")
  Optional<MileageAccrual> findByPaymentIdForUpdate(@Param("paymentId") UUID paymentId);

  @Query(
      "select coalesce(sum(a.accrualAmount), 0) from MileageAccrual a"
          + " where a.buyerId = :buyerId and a.status ="
          + " com.commercesettle.payment.domain.model.MileageAccrualStatus.PENDING")
  long sumPendingByBuyerId(@Param("buyerId") UUID buyerId);
}
