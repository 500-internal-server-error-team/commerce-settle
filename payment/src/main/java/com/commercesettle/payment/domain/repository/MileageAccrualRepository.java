package com.commercesettle.payment.domain.repository;

import com.commercesettle.payment.domain.model.MileageAccrual;
import java.util.Optional;
import java.util.UUID;

public interface MileageAccrualRepository {
  MileageAccrual save(MileageAccrual accrual);

  /** 원결제의 적립을 현재 트랜잭션이 끝날 때까지 잠그고 읽는다. */
  Optional<MileageAccrual> findByPaymentIdForUpdate(UUID paymentId);

  /** 구매자의 적립 예정 합계. 잔액·가용액과 별개다. */
  long sumPendingByBuyerId(UUID buyerId);
}
