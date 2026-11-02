package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.RefundView;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매자 본인의 금융 환불 진행을 조회한다. 없거나 다른 구매자의 환불은 구분하지 않고 빈 값이다. */
@Service
public class RefundQueryService {
  private final RefundRepository refunds;
  private final RefundSagaRepository sagas;
  private final PaymentRepository payments;

  RefundQueryService(
      RefundRepository refunds, RefundSagaRepository sagas, PaymentRepository payments) {
    this.refunds = refunds;
    this.sagas = sagas;
    this.payments = payments;
  }

  @Transactional(readOnly = true)
  public Optional<RefundView> get(UUID buyerId, UUID refundId) {
    return refunds
        .findById(refundId)
        .filter(
            refund ->
                payments
                    .findById(refund.getPaymentId())
                    .filter(payment -> payment.getBuyerId().equals(buyerId))
                    .isPresent())
        .map(refund -> RefundView.of(refund, sagas.findByRefundId(refund.getId()).orElseThrow()));
  }
}
