package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundSagaStep;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRefundSagaRepository extends JpaRepository<RefundSaga, UUID> {
  Optional<RefundSaga> findByRefundId(UUID refundId);

  List<RefundSaga> findByStepInOrderByUpdatedAtAscIdAsc(
      Collection<RefundSagaStep> steps, Pageable pageable);
}
