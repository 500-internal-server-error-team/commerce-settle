package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundStatus;
import com.commercesettle.payment.domain.repository.RefundRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaRefundRepository implements RefundRepository {
  // uk_refunds_payment_active의 대상 상태와 같다.
  private static final Set<RefundStatus> ACTIVE =
      EnumSet.complementOf(EnumSet.of(RefundStatus.DECLINED));

  private final SpringDataRefundRepository repository;
  private final NamedParameterJdbcTemplate jdbc;

  JpaRefundRepository(SpringDataRefundRepository repository, NamedParameterJdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Override
  public java.util.List<Refund> findByBuyer(UUID buyerId, long offset, int size) {
    return repository.findByBuyer(buyerId, offset, size);
  }

  @Override
  public long countByBuyer(UUID buyerId) {
    return repository.countByBuyer(buyerId);
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
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Refund> findByIdForUpdate(UUID id) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    return repository.findByIdForUpdate(id);
  }

  @Override
  public Optional<Refund> findByOrderRefundRequestId(UUID orderRefundRequestId) {
    return repository.findByOrderRefundRequestId(orderRefundRequestId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Refund> findByBusinessKeyForUpdate(UUID businessKey) {
    TransactionLimits.apply(jdbc.getJdbcTemplate());
    return repository.findByBusinessKeyForUpdate(businessKey);
  }

  @Override
  public Optional<Refund> findActiveByPaymentId(UUID paymentId) {
    return repository.findFirstByPaymentIdAndStatusIn(paymentId, ACTIVE);
  }
}
