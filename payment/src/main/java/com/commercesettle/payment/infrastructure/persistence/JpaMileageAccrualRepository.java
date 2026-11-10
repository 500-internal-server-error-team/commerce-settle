package com.commercesettle.payment.infrastructure.persistence;

import com.commercesettle.payment.domain.model.MileageAccrual;
import com.commercesettle.payment.domain.repository.MileageAccrualRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaMileageAccrualRepository implements MileageAccrualRepository {
  private final SpringDataMileageAccrualRepository repository;

  JpaMileageAccrualRepository(SpringDataMileageAccrualRepository repository) {
    this.repository = repository;
  }

  @Override
  public MileageAccrual save(MileageAccrual accrual) {
    return repository.save(accrual);
  }

  @Override
  public Optional<MileageAccrual> findByPaymentIdForUpdate(UUID paymentId) {
    return repository.findByPaymentIdForUpdate(paymentId);
  }

  @Override
  public long sumPendingByBuyerId(UUID buyerId) {
    return repository.sumPendingByBuyerId(buyerId);
  }
}
