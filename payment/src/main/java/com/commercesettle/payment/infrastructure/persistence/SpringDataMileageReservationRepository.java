package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageReservation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMileageReservationRepository extends JpaRepository<MileageReservation, UUID> {
  /** 과거 회차의 해제 이력은 보존하고 현재 회차의 예약만 읽는다. */
  @Query(
      """
      SELECT r FROM MileageReservation r, Payment p
      WHERE r.paymentId=:paymentId AND p.id=r.paymentId AND r.cycleId=p.currentCycleId
      """)
  Optional<MileageReservation> findByPaymentId(@Param("paymentId") UUID paymentId);
}
