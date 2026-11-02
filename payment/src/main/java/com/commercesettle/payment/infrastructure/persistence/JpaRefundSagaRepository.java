package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundSagaStep;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
class JpaRefundSagaRepository implements RefundSagaRepository {
  private static final List<RefundSagaStep> AWAITING_SETTLEMENT =
      Arrays.stream(RefundSagaStep.values()).filter(RefundSagaStep::awaitsSettlement).toList();

  private final SpringDataRefundSagaRepository repository;

  JpaRefundSagaRepository(SpringDataRefundSagaRepository repository) {
    this.repository = repository;
  }

  @Override
  public RefundSaga save(RefundSaga saga) {
    return repository.save(saga);
  }

  @Override
  public Optional<RefundSaga> findByRefundId(UUID refundId) {
    return repository.findByRefundId(refundId);
  }

  @Override
  public List<RefundSaga> findAwaitingSettlement(int limit) {
    return repository.findByStepInOrderByUpdatedAtAscIdAsc(
        AWAITING_SETTLEMENT, PageRequest.of(0, limit));
  }
}
