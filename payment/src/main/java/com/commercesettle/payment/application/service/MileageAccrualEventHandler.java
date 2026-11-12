package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.MileageAccrualConfirmedPayload;
import com.commercesettle.payment.application.event.MileageAccrualScheduledPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.model.MileageAccrual;
import com.commercesettle.payment.domain.model.MileageAccrualStatus;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentStatus;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundStatus;
import com.commercesettle.payment.domain.repository.MileageAccrualRepository;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Commerce의 구매 적립 예정·확정을 결제 요청 처리기와 같은 선점·실패 기록 규칙 아래 반영한다. 예정은 원결제·구매자·외부 결제액·쿠폰 없음과 정책 사본의 재계산
 * 금액을 확인해 PENDING으로 기록하고 잔액은 바꾸지 않는다. 확정은 PENDING을 AVAILABLE로 바꾸면서 지갑(없으면 0점으로 연다)에 더하고 ACCRUAL 원장을
 * 남긴다. 확정 전 환불이 완료되면 {@link #cancelOnRefundCompleted}가 같은 환불 완료 트랜잭션에서 PENDING을 취소한다.
 *
 * <p>같은 이벤트의 재전달과 이미 반영한 결과는 바꾸지 않고 APPLIED로 끝낸다. 예정보다 먼저 온 확정은 시도 상한 안에서 다시 처리하고, 계약과 맞지 않는 입력은 확인
 * 대상으로 남긴다.
 */
@Component
public class MileageAccrualEventHandler {
  static final String PAYMENT_NOT_FOUND = "MILEAGE_ACCRUAL_PAYMENT_NOT_FOUND";
  static final String PAYMENT_NOT_CONFIRMED = "MILEAGE_ACCRUAL_PAYMENT_NOT_CONFIRMED";
  static final String PAYMENT_MISMATCH = "MILEAGE_ACCRUAL_PAYMENT_MISMATCH";
  static final String POLICY_INVALID = "MILEAGE_ACCRUAL_POLICY_INVALID";
  static final String CONFLICT = "MILEAGE_ACCRUAL_CONFLICT";
  static final String NOT_SCHEDULED = "MILEAGE_ACCRUAL_NOT_SCHEDULED";
  static final String CANCELLED = "MILEAGE_ACCRUAL_CANCELLED";
  static final String REFUND_IN_PROGRESS = "MILEAGE_ACCRUAL_REFUND_IN_PROGRESS";

  private static final Logger LOG = LoggerFactory.getLogger(MileageAccrualEventHandler.class);

  private final InboxStore inbox;
  private final PaymentRepository payments;
  private final RefundRepository refunds;
  private final MileageAccrualRepository accruals;
  private final MileageWalletRepository wallets;
  private final MileageEntryRepository entries;

  MileageAccrualEventHandler(
      InboxStore inbox,
      PaymentRepository payments,
      RefundRepository refunds,
      MileageAccrualRepository accruals,
      MileageWalletRepository wallets,
      MileageEntryRepository entries) {
    this.inbox = inbox;
    this.payments = payments;
    this.refunds = refunds;
    this.accruals = accruals;
    this.wallets = wallets;
    this.entries = entries;
  }

  /** 이 처리기가 반영하는 이벤트인지 확인한다. */
  public boolean handles(EventEnvelope<?> event) {
    return event.payload() instanceof MileageAccrualScheduledPayload
        || event.payload() instanceof MileageAccrualConfirmedPayload;
  }

  /** 선점한 Inbox 행이 주문 잠금 아래 있는 트랜잭션에서 호출한다. */
  public void handle(WaitingInboxEvent claimed, Instant now) {
    switch (claimed.event().payload()) {
      case MileageAccrualScheduledPayload scheduled -> schedule(claimed, scheduled, now);
      case MileageAccrualConfirmedPayload confirmed -> confirm(claimed, confirmed, now);
      default -> throw new IllegalArgumentException("Not a mileage accrual event");
    }
  }

  /**
   * 환불이 완료되는 트랜잭션에서 호출한다. 원결제의 적립이 PENDING이면 취소한다. 잔액에 넣은 적이 없으므로 지갑은 잠그지 않는다. 적립이 없거나 이미 끝난 상태면
   * 아무것도 하지 않는다. AVAILABLE이면 구매확정 뒤 환불이므로 상태를 바꾸지 않고 경고만 남긴다(구매확정 뒤 환불 경로는 없다).
   */
  public void cancelOnRefundCompleted(Refund refund, Instant now) {
    Optional<MileageAccrual> found = accruals.findByPaymentIdForUpdate(refund.getPaymentId());
    if (found.isEmpty()) {
      return;
    }
    MileageAccrual accrual = found.get();
    if (accrual.getStatus() == MileageAccrualStatus.PENDING) {
      accrual.cancel(now);
      accruals.save(accrual);
      log(accrual, "Mileage accrual cancelled by refund");
    } else if (accrual.getStatus() == MileageAccrualStatus.AVAILABLE) {
      LOG.atWarn()
          .addKeyValue("event", "payment.mileage_accrual")
          .addKeyValue("paymentId", accrual.getPaymentId())
          .addKeyValue("refundId", refund.getId())
          .addKeyValue("result", accrual.getStatus().name())
          .log("Refund completed after mileage accrual became available");
    }
  }

  private void schedule(
      WaitingInboxEvent claimed, MileageAccrualScheduledPayload request, Instant now) {
    Optional<Payment> found = payments.findById(request.paymentId());
    if (found.isEmpty()) {
      review(claimed, PAYMENT_NOT_FOUND, now);
      return;
    }
    Payment payment = found.get();
    if (!payment.getOrderId().equals(request.orderId())
        || !payment.getBuyerId().equals(request.buyerId())) {
      review(claimed, PAYMENT_MISMATCH, now);
      return;
    }
    if (payment.getStatus() != PaymentStatus.CONFIRMED) {
      // Commerce는 PaymentConfirmed를 반영한 뒤에만 예정을 보낸다. 확정되지 않은 결제의 예정은 계약 위반이다.
      review(claimed, PAYMENT_NOT_CONFIRMED, now);
      return;
    }
    Optional<MileageAccrual.Policy> policy = policy(payment, request);
    if (policy.isEmpty()) {
      review(claimed, POLICY_INVALID, now);
      return;
    }
    Optional<MileageAccrual> existing = accruals.findByPaymentIdForUpdate(payment.getId());
    if (existing.isPresent()) {
      if (existing.get().matches(payment, policy.get())) {
        inbox.markApplied(claimed.inboxId(), existing.get().getId(), now);
      } else {
        review(claimed, CONFLICT, now);
      }
      return;
    }
    MileageAccrual accrual =
        MileageAccrual.schedule(
            payment, policy.get(), request.paymentConfirmedAt().toInstant(), now);
    // 예정보다 환불 완료가 먼저 반영됐다면 취소할 기회가 지났으므로 바로 취소로 기록한다.
    if (refunds
        .findActiveByPaymentId(payment.getId())
        .filter(refund -> refund.getStatus() == RefundStatus.COMPLETED)
        .isPresent()) {
      accrual.cancel(now);
    }
    accruals.save(accrual);
    inbox.markApplied(claimed.inboxId(), accrual.getId(), now);
    log(accrual, "Mileage accrual scheduled");
  }

  private void confirm(
      WaitingInboxEvent claimed, MileageAccrualConfirmedPayload request, Instant now) {
    Optional<MileageAccrual> found = accruals.findByPaymentIdForUpdate(request.paymentId());
    if (found.isEmpty()) {
      // 같은 key라 예정이 먼저 오지만, 예정이 아직 처리되지 않았거나 다시 처리 중일 수 있다.
      defer(claimed, NOT_SCHEDULED, now);
      return;
    }
    MileageAccrual accrual = found.get();
    if (!accrual.getOrderId().equals(request.orderId())
        || accrual.getAccrualAmount() != request.accrualAmount()) {
      review(claimed, CONFLICT, now);
      return;
    }
    switch (accrual.getStatus()) {
      case AVAILABLE -> {
        inbox.markApplied(claimed.inboxId(), accrual.getId(), now);
        return;
      }
      case CANCELLED -> {
        review(claimed, CANCELLED, now);
        return;
      }
      case PENDING -> {}
    }
    Optional<Refund> refund = refunds.findActiveByPaymentId(accrual.getPaymentId());
    if (refund.isPresent()) {
      // 구매확정은 진행 중인 환불이 없을 때만 일어난다. 환불과 엇갈린 확정은 지급하지 않는다.
      review(claimed, REFUND_IN_PROGRESS, now);
      return;
    }
    MileageWallet wallet =
        wallets
            .findByBuyerIdForUpdate(accrual.getBuyerId())
            // 첫 적립이면 0점 지갑을 연다. 같은 구매자의 동시 생성은 buyer_id 유일 조건이 막고, 실패한 쪽은 롤백 뒤 다시 처리한다.
            .orElseGet(
                () -> wallets.saveAndFlush(MileageWallet.open(accrual.getBuyerId(), 0, now)));
    accrual.makeAvailable(request.confirmedAt().toInstant(), now);
    wallet.accrue(accrual.getAccrualAmount(), now);
    entries.save(MileageEntry.accrual(wallet, accrual, now));
    wallets.save(wallet);
    accruals.save(accrual);
    inbox.markApplied(claimed.inboxId(), accrual.getId(), now);
    log(accrual, "Mileage accrual became available");
  }

  /** 외부 결제액·쿠폰 없음과 정책 사본의 재계산 금액이 맞을 때만 정책을 돌려준다. */
  private static Optional<MileageAccrual.Policy> policy(
      Payment payment, MileageAccrualScheduledPayload request) {
    if (payment.getAmounts().couponAmount() != 0
        || request.baseAmount() != payment.getAmounts().externalAmount()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new MileageAccrual.Policy(
              request.baseAmount(),
              request.rateBasisPoints(),
              request.perOrderCap(),
              request.accrualAmount(),
              request.accrualPolicyVersion()));
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return Optional.empty();
    }
  }

  private void defer(WaitingInboxEvent claimed, String errorCode, Instant now) {
    int attempts = claimed.attempts();
    Instant retry =
        attempts < ApplyOrderPaymentRequestService.MAX_ATTEMPTS
            ? now.plus(PaymentRetryPolicy.delayAfter(attempts))
            : null;
    inbox.recordFailure(claimed.inboxId(), claimed.version(), errorCode, retry, now);
    warn(claimed, retry == null ? "REVIEW_REQUIRED" : "WAITING", errorCode);
  }

  private void review(WaitingInboxEvent claimed, String errorCode, Instant now) {
    inbox.markReviewRequired(claimed.inboxId(), errorCode, now);
    warn(claimed, "REVIEW_REQUIRED", errorCode);
  }

  private static void log(MileageAccrual accrual, String message) {
    LOG.atDebug()
        .addKeyValue("event", "payment.mileage_accrual")
        .addKeyValue("paymentId", accrual.getPaymentId())
        .addKeyValue("accrualAmount", accrual.getAccrualAmount())
        .addKeyValue("result", accrual.getStatus().name())
        .log(message);
  }

  private static void warn(WaitingInboxEvent claimed, String result, String errorCode) {
    // 선행 결과를 기다리는 WAITING은 다시 시도하는 정상 흐름이다.
    (result.equals("WAITING") ? LOG.atDebug() : LOG.atWarn())
        .addKeyValue("event", "payment.mileage_accrual")
        .addKeyValue("eventId", claimed.event().eventId())
        .addKeyValue("eventType", claimed.event().eventType())
        .addKeyValue("result", result)
        .addKeyValue("errorCode", errorCode)
        .log("Mileage accrual event was not applied");
  }
}
