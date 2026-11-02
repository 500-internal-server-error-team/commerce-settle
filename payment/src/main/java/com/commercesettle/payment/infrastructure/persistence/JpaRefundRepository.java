package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundStatus;
import com.commercesettle.payment.domain.repository.RefundRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaRefundRepository implements RefundRepository {
  // uk_refunds_payment_active의 대상 상태와 같다.
  private static final Set<RefundStatus> ACTIVE =
      EnumSet.complementOf(EnumSet.of(RefundStatus.DECLINED));

  private final SpringDataRefundRepository repository;

  JpaRefundRepository(SpringDataRefundRepository repository) {
    this.repository = repository;
  }

  @Override
  public Refund save(Refund refund) {
    return repository.save(refund);
  }

  @Override
  public Optional<Refund> findById(UUID id) {
    return repository.findById(id);
  }

  @Override
  public Optional<Refund> findByIdForUpdate(UUID id) {
    return repository.findByIdForUpdate(id);
  }

  @Override
  public Optional<Refund> findByOrderRefundRequestId(UUID orderRefundRequestId) {
    return repository.findByOrderRefundRequestId(orderRefundRequestId);
  }

  @Override
  public Optional<Refund> findByBusinessKeyForUpdate(UUID businessKey) {
    return repository.findByBusinessKeyForUpdate(businessKey);
  }

  @Override
  public Optional<Refund> findActiveByPaymentId(UUID paymentId) {
    return repository.findFirstByPaymentIdAndStatusIn(paymentId, ACTIVE);
  }
}
