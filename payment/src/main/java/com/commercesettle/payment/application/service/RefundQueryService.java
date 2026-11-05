package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.RefundView;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.time.Instant;
import java.util.List;
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

  public record Summary(
      UUID id,
      UUID paymentId,
      long externalAmount,
      long mileageAmount,
      String status,
      long version,
      Instant createdAt) {
    static Summary from(Refund r) {
      return new Summary(
          r.getId(),
          r.getPaymentId(),
          r.getExternalAmount(),
          r.getMileageAmount(),
          r.getStatus().name(),
          r.getVersion(),
          r.getCreatedAt());
    }
  }

  public record Page(List<Summary> items, int page, int size, long totalElements) {
    public int totalPages() {
      return (int)
          Math.min(Integer.MAX_VALUE, totalElements == 0 ? 0 : (totalElements - 1) / size + 1);
    }
  }

  @Transactional(readOnly = true)
  public Page list(UUID buyerId, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page");
    return new Page(
        refunds.findByBuyer(buyerId, (long) page * size, size).stream().map(Summary::from).toList(),
        page,
        size,
        refunds.countByBuyer(buyerId));
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
