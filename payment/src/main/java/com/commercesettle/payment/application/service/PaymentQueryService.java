package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.PaymentPageView;
import com.commercesettle.payment.application.dto.PaymentView;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.domain.exception.PaymentNotFoundException;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매자 본인의 결제 진행을 조회한다. 다른 구매자의 결제와 없는 결제는 구분하지 않는다. */
@Service
public class PaymentQueryService {
  private final PaymentRepository payments;
  private final InboxStore inbox;

  public PaymentQueryService(PaymentRepository payments, InboxStore inbox) {
    this.payments = payments;
    this.inbox = inbox;
  }

  @Transactional(readOnly = true)
  public PaymentView get(UUID buyerId, UUID paymentId) {
    return payments
        .findByIdAndBuyerId(paymentId, buyerId)
        .map(this::view)
        .orElseThrow(PaymentNotFoundException::new);
  }

  @Transactional(readOnly = true)
  public PaymentPageView list(UUID buyerId, UUID orderId, int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid payment page");
    }
    long total = payments.countByBuyer(buyerId, orderId);
    // 시작 위치가 int를 넘는 페이지는 행이 있을 수 없으므로 조회하지 않는다.
    List<PaymentView> items =
        (long) page * size > Integer.MAX_VALUE
            ? List.of()
            : payments.findByBuyer(buyerId, orderId, page, size).stream().map(this::view).toList();
    return new PaymentPageView(items, page, size, total);
  }

  /** 결제수단은 결제에 저장하지 않으므로 결제를 처음 만든 요청 원문에서 읽는다. 외부 결제액이 없으면 결제수단도 없다. */
  private PaymentView view(Payment payment) {
    String method =
        payment.getAmounts().externalAmount() == 0
            ? null
            : inbox
                .findOriginalRequest(payment.getId())
                .map(OrderPaymentRequestedPayload::externalMethod)
                .map(Enum::name)
                .orElse(null);
    return PaymentView.of(payment, method);
  }
}
