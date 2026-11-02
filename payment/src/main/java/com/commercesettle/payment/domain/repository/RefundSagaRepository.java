package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.RefundSaga;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundSagaRepository {
  RefundSaga save(RefundSaga saga);

  Optional<RefundSaga> findByRefundId(UUID refundId);

  /** 정산 응답을 기다리는 SAGA를 오래 기다린 순서로 limit개까지 읽는다. */
  List<RefundSaga> findAwaitingSettlement(int limit);
}
