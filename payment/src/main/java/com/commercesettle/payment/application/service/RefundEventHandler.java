package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.RefundAcceptedPayload;
import com.commercesettle.payment.application.event.RefundApprovedPayload;
import com.commercesettle.payment.application.event.RefundLedgerReversedPayload;
import com.commercesettle.payment.application.event.RefundOrderRestoredPayload;
import com.commercesettle.payment.application.event.RefundReservationReleasedPayload;
import com.commercesettle.payment.application.event.RefundReservationRequestedPayload;
import com.commercesettle.payment.application.event.RefundReservationResultPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.application.service.RefundSettlementReplyService.Outcome;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentStatus;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundSagaStep;
import com.commercesettle.payment.domain.model.RefundStatus;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 선점한 Commerce 환불 이벤트와 Settlement 환불 응답을 결제 요청 처리기와 같은 트랜잭션에서 반영한다. RefundApproved는 원결제 전액 환불과
 * SAGA를 접수하고 RefundAccepted·RefundReservationRequested를 함께 저장한다. RefundOrderRestored는 주문 복구 확인을 반영해
 * 환불을 끝내고 원결제의 구매 적립 예정을 취소한다. Settlement 응답은 {@link RefundSettlementReplyService}가 반영하고, 기다리지 않는
 * 단계·다른 원결제·다른 예약의 응답은 확인 대상으로 남긴다.
 *
 * <p>같은 취소·반품 요청의 재전달은 기존 환불로 연결하고, 같은 원결제의 다른 요청은 중복 환불이므로 확인 대상으로 남긴다. 아직 확정되지 않은 원결제는 결과가 날 때까지
 * 다시 처리한다.
 */
@Component
public class RefundEventHandler {
  static final String PAYMENT_NOT_FOUND = "REFUND_PAYMENT_NOT_FOUND";
  static final String PAYMENT_MISMATCH = "REFUND_PAYMENT_MISMATCH";
  static final String PAYMENT_NOT_CONFIRMED = "REFUND_PAYMENT_NOT_CONFIRMED";
  static final String PAYMENT_NOT_REFUNDABLE = "REFUND_PAYMENT_NOT_REFUNDABLE";
  static final String REQUEST_CONFLICT = "REFUND_REQUEST_CONFLICT";
  static final String ALREADY_IN_PROGRESS = "REFUND_ALREADY_IN_PROGRESS";
  static final String ALREADY_COMPLETED = "REFUND_ALREADY_COMPLETED";
  static final String REFUND_NOT_FOUND = "REFUND_NOT_FOUND";
  static final String ORDER_RESTORED_UNEXPECTED = "REFUND_ORDER_RESTORED_UNEXPECTED";
  static final String SETTLEMENT_REPLY_REJECTED = "REFUND_SETTLEMENT_REPLY_REJECTED";

  private static final Logger LOG = LoggerFactory.getLogger(RefundEventHandler.class);

  private final InboxStore inbox;
  private final PaymentRepository payments;
  private final RefundRepository refunds;
  private final RefundSagaRepository sagas;
  private final RefundEvents events;
  private final RefundSettlementReplyService replies;
  private final MileageAccrualEventHandler accruals;

  RefundEventHandler(
      InboxStore inbox,
      PaymentRepository payments,
      RefundRepository refunds,
      RefundSagaRepository sagas,
      RefundEvents events,
      RefundSettlementReplyService replies,
      MileageAccrualEventHandler accruals) {
    this.inbox = inbox;
    this.payments = payments;
    this.refunds = refunds;
    this.sagas = sagas;
    this.events = events;
    this.replies = replies;
    this.accruals = accruals;
  }

  /** 이 처리기가 반영하는 이벤트인지 확인한다. */
  public boolean handles(EventEnvelope<?> event) {
    return event.payload() instanceof RefundApprovedPayload
        || event.payload() instanceof RefundOrderRestoredPayload
        || event.payload() instanceof RefundReservationResultPayload
        || event.payload() instanceof RefundLedgerReversedPayload
        || event.payload() instanceof RefundReservationReleasedPayload;
  }

  /** 선점한 Inbox 행이 잠긴 트랜잭션에서 호출한다. */
  public void handle(WaitingInboxEvent claimed, Instant now) {
    switch (claimed.event().payload()) {
      case RefundApprovedPayload approved -> accept(claimed, approved, now);
      case RefundOrderRestoredPayload restored -> orderRestored(claimed, restored, now);
      case RefundReservationResultPayload reply ->
          settlementReplied(claimed, reply.refundId(), replies.applyReservationResult(reply), now);
      case RefundLedgerReversedPayload reply ->
          settlementReplied(claimed, reply.refundId(), replies.applyLedgerReversed(reply), now);
      case RefundReservationReleasedPayload reply ->
          settlementReplied(
              claimed, reply.refundId(), replies.applyReservationReleased(reply), now);
      default -> throw new IllegalArgumentException("Not a refund event");
    }
  }

  private void accept(WaitingInboxEvent claimed, RefundApprovedPayload request, Instant now) {
    Optional<Refund> same = refunds.findByOrderRefundRequestId(request.requestId());
    if (same.isPresent()) {
      if (!same.get().getPaymentId().equals(request.paymentId())) {
        review(claimed, REQUEST_CONFLICT, now);
        return;
      }
      // 같은 요청의 재전달이다. 새 환불이나 이벤트를 만들지 않는다.
      inbox.markApplied(claimed.inboxId(), same.get().getId(), now);
      return;
    }
    Optional<Payment> found = payments.findById(request.paymentId());
    if (found.isEmpty()) {
      review(claimed, PAYMENT_NOT_FOUND, now);
      return;
    }
    Payment payment = found.get();
    if (!payment.getOrderId().equals(request.orderId())) {
      review(claimed, PAYMENT_MISMATCH, now);
      return;
    }
    if (payment.getStatus() != PaymentStatus.CONFIRMED) {
      if (isUnsettled(payment.getStatus())) {
        defer(claimed, now);
      } else {
        review(claimed, PAYMENT_NOT_REFUNDABLE, now);
      }
      return;
    }
    Optional<Refund> active = refunds.findActiveByPaymentId(payment.getId());
    if (active.isPresent()) {
      review(
          claimed,
          active.get().getStatus() == RefundStatus.COMPLETED
              ? ALREADY_COMPLETED
              : ALREADY_IN_PROGRESS,
          now);
      return;
    }

    Refund refund = refunds.save(Refund.accept(payment, request.requestId(), now));
    sagas.save(RefundSaga.start(refund, now));
    events.append(
        "RefundAccepted",
        payment,
        refund,
        new RefundAcceptedPayload(
            refund.getId(), payment.getId(), payment.getOrderId(), request.requestId()),
        now);
    events.append(
        "RefundReservationRequested",
        payment,
        refund,
        new RefundReservationRequestedPayload(
            refund.getId(), payment.getId(), payment.getAmounts().sellerNetAmount()),
        now);
    inbox.markApplied(claimed.inboxId(), refund.getId(), now);
    LOG.atDebug()
        .addKeyValue("event", "payment.refund")
        .addKeyValue("refundId", refund.getId())
        .addKeyValue("paymentId", payment.getId())
        .addKeyValue("result", RefundStatus.RESERVING.name())
        .log("Refund accepted");
  }

  private void orderRestored(
      WaitingInboxEvent claimed, RefundOrderRestoredPayload confirmation, Instant now) {
    Optional<Refund> found = refunds.findByIdForUpdate(confirmation.refundId());
    if (found.isEmpty()) {
      review(claimed, REFUND_NOT_FOUND, now);
      return;
    }
    Refund refund = found.get();
    Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
    if (!refund.getPaymentId().equals(confirmation.paymentId())
        || !payment.getOrderId().equals(confirmation.orderId())) {
      review(claimed, PAYMENT_MISMATCH, now);
      return;
    }
    if (refund.getStatus() == RefundStatus.COMPLETED) {
      inbox.markApplied(claimed.inboxId(), refund.getId(), now);
      return;
    }
    RefundSaga saga = sagas.findByRefundId(refund.getId()).orElseThrow();
    if (refund.getStatus() != RefundStatus.INTERNAL_PENDING
        || saga.getStep() != RefundSagaStep.ORDER_RESTORE_PENDING) {
      // RefundCompleted를 보내기 전에는 주문 복구 확인이 올 수 없다. 순서를 건너뛴 확인으로 완료하지 않는다.
      review(claimed, ORDER_RESTORED_UNEXPECTED, now);
      return;
    }
    saga.orderRestored(now);
    refund.complete(now);
    sagas.save(saga);
    refunds.save(refund);
    // 구매확정 전 환불이 끝났다. 아직 잔액에 넣지 않은 적립 예정을 같은 트랜잭션에서 취소한다.
    accruals.cancelOnRefundCompleted(refund, now);
    inbox.markApplied(claimed.inboxId(), refund.getId(), now);
    LOG.atDebug()
        .addKeyValue("event", "payment.refund")
        .addKeyValue("refundId", refund.getId())
        .addKeyValue("paymentId", refund.getPaymentId())
        .addKeyValue("result", RefundStatus.COMPLETED.name())
        .log("Refund completed after order restoration");
  }

  /** 반영했거나 이미 반영한 응답, 계속 기다리라는 예약 결과는 APPLIED로 끝낸다. 반영하지 않은 응답은 확인 대상으로 남긴다. */
  private void settlementReplied(
      WaitingInboxEvent claimed, UUID refundId, Outcome outcome, Instant now) {
    if (outcome == Outcome.REJECTED) {
      review(claimed, SETTLEMENT_REPLY_REJECTED, now);
      return;
    }
    inbox.markApplied(claimed.inboxId(), refundId, now);
  }

  private static boolean isUnsettled(PaymentStatus status) {
    return status == PaymentStatus.PENDING
        || status == PaymentStatus.PROCESSING
        || status == PaymentStatus.UNKNOWN;
  }

  /** 원결제 결과가 아직 없다. 시도 상한 안에서 다시 처리하고, 상한에 닿으면 확인 대상으로 남긴다. */
  private void defer(WaitingInboxEvent claimed, Instant now) {
    int attempts = claimed.attempts();
    Instant retry =
        attempts < ApplyOrderPaymentRequestService.MAX_ATTEMPTS
            ? now.plus(PaymentRetryPolicy.delayAfter(attempts))
            : null;
    inbox.recordFailure(claimed.inboxId(), claimed.version(), PAYMENT_NOT_CONFIRMED, retry, now);
    log(claimed, retry == null ? "REVIEW_REQUIRED" : "WAITING", PAYMENT_NOT_CONFIRMED);
  }

  private void review(WaitingInboxEvent claimed, String errorCode, Instant now) {
    inbox.markReviewRequired(claimed.inboxId(), errorCode, now);
    log(claimed, "REVIEW_REQUIRED", errorCode);
  }

  private static void log(WaitingInboxEvent claimed, String result, String errorCode) {
    // 선행 결과를 기다리는 WAITING은 다시 시도하는 정상 흐름이다.
    (result.equals("WAITING") ? LOG.atDebug() : LOG.atWarn())
        .addKeyValue("event", "payment.refund")
        .addKeyValue("eventId", claimed.event().eventId())
        .addKeyValue("eventType", claimed.event().eventType())
        .addKeyValue("result", result)
        .addKeyValue("errorCode", errorCode)
        .log("Refund event was not applied");
  }
}
