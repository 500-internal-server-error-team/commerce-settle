package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.exception.InvalidPaymentAmountException;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WAITING Inbox의 결제 요청을 검사해 결제와 승인 작업을 만들고 처리 결과를 기록한다. 재시도해도 결과가 같은 업무상 거절은 예외 없이 REVIEW_REQUIRED로
 * 남기고, 그 밖의 예외는 롤백되어 WAITING으로 다시 처리된다.
 */
@Service
public class ApplyOrderPaymentRequestService {
  private static final Logger LOG = LoggerFactory.getLogger(ApplyOrderPaymentRequestService.class);

  private final InboxStore inbox;
  private final PaymentRepository payments;
  private final PaymentJobRepository jobs;
  private final Clock clock;

  public ApplyOrderPaymentRequestService(
      InboxStore inbox, PaymentRepository payments, PaymentJobRepository jobs, Clock clock) {
    this.inbox = inbox;
    this.payments = payments;
    this.jobs = jobs;
    this.clock = clock;
  }

  /** 처리할 WAITING 하나를 처리하고, 처리한 이벤트가 있었는지 반환한다. */
  @Transactional
  public boolean processNext() {
    Instant now = clock.instant();
    Optional<WaitingInboxEvent> claimed = inbox.claimNextWaiting(now);
    if (claimed.isEmpty()) {
      return false;
    }
    UUID inboxId = claimed.get().inboxId();
    OrderPaymentRequestedPayload payload = claimed.get().event().payload();

    if (payload.status() != OrderPaymentRequestedPayload.Status.PAYMENT_PENDING) {
      return review(claimed.get(), "PAYMENT_REQUEST_ORDER_NOT_PENDING", now);
    }
    Optional<PaymentAmounts> amounts = toAmounts(payload);
    if (amounts.isEmpty()) {
      return review(claimed.get(), "PAYMENT_REQUEST_AMOUNT_INVALID", now);
    }
    if (!matchesCommerceAmounts(payload, amounts.get())) {
      return review(claimed.get(), "PAYMENT_REQUEST_AMOUNT_MISMATCH", now);
    }

    Optional<Payment> existing = payments.findByOrderId(payload.orderId());
    if (existing.isPresent()) {
      if (!isSameRequest(existing.get(), payload, amounts.get())) {
        return review(claimed.get(), "PAYMENT_REQUEST_ORDER_CONFLICT", now);
      }
      inbox.markApplied(inboxId, existing.get().getId(), now);
      return true;
    }

    Payment payment = createPayment(payload, amounts.get(), now);
    inbox.markApplied(inboxId, payment.getId(), now);
    return true;
  }

  /**
   * 확인 대상으로 남기고 거절 이유를 로그에 남긴다. Inbox에는 사유 컬럼이 없으므로 이유는 로그로 추적한다. 결제수단 참조 같은 payload 원문은 기록하지 않는다.
   */
  private boolean review(WaitingInboxEvent claimed, String errorCode, Instant now) {
    inbox.markReviewRequired(claimed.inboxId(), now);
    LOG.atWarn()
        .addKeyValue("event", "payment.accept")
        .addKeyValue("eventId", claimed.event().eventId())
        .addKeyValue("orderId", claimed.event().payload().orderId())
        .addKeyValue("result", "REVIEW_REQUIRED")
        .addKeyValue("errorCode", errorCode)
        .log("Payment request needs review");
    return true;
  }

  /** 금액 규칙을 통과하면 PaymentAmounts를, 위반하면 빈 값을 돌려준다. */
  private Optional<PaymentAmounts> toAmounts(OrderPaymentRequestedPayload payload) {
    try {
      return Optional.of(
          new PaymentAmounts(
              payload.grossAmount(),
              payload.sellerCouponAmount(),
              payload.platformCouponAmount(),
              payload.mileageAmount()));
    } catch (InvalidPaymentAmountException exception) {
      return Optional.empty();
    }
  }

  /** 커머스가 계산해 보낸 B·T·E·couponAmount가 재계산 값과 같은지 확인한다. */
  private boolean matchesCommerceAmounts(
      OrderPaymentRequestedPayload payload, PaymentAmounts amounts) {
    return payload.settlementBaseAmount() == amounts.settlementBaseAmount()
        && payload.totalAmount() == amounts.totalAmount()
        && payload.externalAmount() == amounts.externalAmount()
        && payload.couponAmount() == amounts.couponAmount();
  }

  /**
   * 이미 있는 결제가 이번 요청과 같은 회차·주문 버전·당사자·금액·정책 버전인지 확인한다. 외부 결제액이 있으면 결제를 처음 만든 요청과 결제수단·계정 참조도 비교한다.
   * 외부 결제액이 0원이면 결제수단 값은 쓰지 않으므로 비교하지 않는다.
   */
  private boolean isSameRequest(
      Payment existing, OrderPaymentRequestedPayload payload, PaymentAmounts amounts) {
    boolean samePayment =
        existing.getCurrentCycleId().equals(payload.paymentCycleId())
            && existing.getOrderVersion() == payload.orderVersion()
            && existing.getBuyerId().equals(payload.buyerId())
            && existing.getSellerId().equals(payload.sellerId())
            && existing.getProductId().equals(payload.productId())
            && existing.getAmounts().equals(amounts)
            && existing.getPolicyVersion().equals(payload.policyVersion());
    if (!samePayment || amounts.externalAmount() == 0) {
      return samePayment;
    }
    return inbox
        .findOriginalRequest(existing.getId())
        .filter(original -> original.externalMethod() == payload.externalMethod())
        .filter(original -> Objects.equals(original.methodAccountRef(), payload.methodAccountRef()))
        .isPresent();
  }

  /** 결제를 만들고 외부 결제액이 있으면 승인 작업도 남긴다. */
  private Payment createPayment(
      OrderPaymentRequestedPayload payload, PaymentAmounts amounts, Instant now) {
    Payment payment =
        payments.save(
            Payment.create(
                payload.orderId(),
                payload.orderVersion(),
                payload.paymentCycleId(),
                payload.buyerId(),
                payload.sellerId(),
                payload.productId(),
                amounts,
                payload.policyVersion(),
                now));
    if (amounts.externalAmount() > 0) {
      jobs.save(PaymentJob.approvalFor(payment, now));
    }
    return payment;
  }
}
