package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.PageView;
import com.commercesettle.payment.application.dto.PaymentAttemptView;
import com.commercesettle.payment.domain.exception.PaymentNotFoundException;
import com.commercesettle.payment.domain.repository.PaymentAttemptRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매자 본인 결제의 외부 승인 시도를 조회한다. 없거나 다른 구매자의 결제는 구분하지 않는다. */
@Service
public class PaymentAttemptQueryService {
  private final PaymentRepository payments;
  private final PaymentAttemptRepository attempts;

  public PaymentAttemptQueryService(PaymentRepository payments, PaymentAttemptRepository attempts) {
    this.payments = payments;
    this.attempts = attempts;
  }

  @Transactional(readOnly = true)
  public PageView<PaymentAttemptView> list(UUID buyerId, UUID paymentId, int page, int size) {
    if (payments.findByIdAndBuyerId(paymentId, buyerId).isEmpty()) {
      throw new PaymentNotFoundException();
    }
    return PageView.read(
        page,
        size,
        attempts.countByPaymentId(paymentId),
        (p, s) ->
            attempts.findByPaymentId(paymentId, p, s).stream()
                .map(PaymentAttemptView::of)
                .toList());
  }
}
