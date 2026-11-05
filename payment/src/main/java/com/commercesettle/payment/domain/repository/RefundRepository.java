package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
  Refund save(Refund refund);

  List<Refund> findByBuyer(UUID buyerId, long offset, int size);

  long countByBuyer(UUID buyerId);

  Optional<Refund> findById(UUID id);

  /** 환불을 현재 트랜잭션이 끝날 때까지 잠그고 읽는다. 같은 환불을 바꾸는 다른 처리는 기다린다. */
  Optional<Refund> findByIdForUpdate(UUID id);

  Optional<Refund> findByOrderRefundRequestId(UUID orderRefundRequestId);

  /** 환불 작업의 업무 키로 환불을 찾아 현재 트랜잭션이 끝날 때까지 잠근다. */
  Optional<Refund> findByBusinessKeyForUpdate(UUID businessKey);

  /** 같은 원결제에 진행 중이거나 완료된 환불. 확정 거절된 환불은 제외한다. */
  Optional<Refund> findActiveByPaymentId(UUID paymentId);
}
