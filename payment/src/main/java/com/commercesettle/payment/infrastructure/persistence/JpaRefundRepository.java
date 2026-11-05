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
    limits();
    return repository.findByIdForUpdate(id);
  }

  @Override
  public Optional<Refund> findByOrderRefundRequestId(UUID orderRefundRequestId) {
    return repository.findByOrderRefundRequestId(orderRefundRequestId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Refund> findByBusinessKeyForUpdate(UUID businessKey) {
    limits();
    return repository.findByBusinessKeyForUpdate(businessKey);
  }

  @Override
  public Optional<Refund> findActiveByPaymentId(UUID paymentId) {
    return repository.findFirstByPaymentIdAndStatusIn(paymentId, ACTIVE);
  }

  /**
   * 환불 행 잠금 전에 현재 트랜잭션의 대기 상한을 건다. 다른 트랜잭션의 잠금은 1초, SQL 한 문장은 5초까지만 기다리고, 넘으면 예외로 트랜잭션 전체를 롤백한다. 같은
   * 트랜잭션의 뒤이은 잠금(지갑·작업)에도 적용되며, 호출자의 더 짧은 상한은 유지한다.
   */
  private void limits() {
    jdbc.getJdbcTemplate()
        .queryForList(
            """
        SELECT set_config(budget.name,
            least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),
                budget.timeout_ms)::text,true)
        FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000))
            AS budget(name,timeout_ms)
        JOIN pg_settings settings ON settings.name=budget.name
        """);
  }
}
