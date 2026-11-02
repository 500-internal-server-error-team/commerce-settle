package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.application.port.UnreadableInboxEventException;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.exception.InvalidPaymentAmountException;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageReservation;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageReservationRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * WAITING Inbox의 결제 요청을 검사해 결제·마일리지 예약·승인 작업을 만들고 처리 결과를 기록한다. 마일리지 전액 결제는 같은 트랜잭션에서 차감·확정한다. 재시도해도
 * 결과가 같은 업무상 거절은 예외 없이 사유와 함께 REVIEW_REQUIRED로 남긴다. 그 밖의 예외는 처리 트랜잭션을 롤백한 뒤 별도 트랜잭션에서 시도 횟수와 사유를
 * 기록하고, 1초부터 두 배씩 최대 60초 뒤에 다시 처리한다. 10번째 실패는 REVIEW_REQUIRED로 남긴다.
 */
@Service
public class ApplyOrderPaymentRequestService {
  static final int MAX_ATTEMPTS = 10;
  static final String PROCESSING_FAILED = "PAYMENT_REQUEST_PROCESSING_FAILED";
  static final String BODY_UNREADABLE = "PAYMENT_REQUEST_BODY_UNREADABLE";

  private static final Logger LOG = LoggerFactory.getLogger(ApplyOrderPaymentRequestService.class);

  private final InboxStore inbox;
  private final PaymentRepository payments;
  private final PaymentJobRepository jobs;
  private final MileageWalletRepository wallets;
  private final MileageReservationRepository reservations;
  private final MileageEntryRepository entries;
  private final PaymentEventOutbox outbox;
  private final TransactionOperations transactions;
  private final Clock clock;

  public ApplyOrderPaymentRequestService(
      InboxStore inbox,
      PaymentRepository payments,
      PaymentJobRepository jobs,
      MileageWalletRepository wallets,
      MileageReservationRepository reservations,
      MileageEntryRepository entries,
      PaymentEventOutbox outbox,
      TransactionOperations transactions,
      Clock clock) {
    this.inbox = inbox;
    this.payments = payments;
    this.jobs = jobs;
    this.wallets = wallets;
    this.reservations = reservations;
    this.entries = entries;
    this.outbox = outbox;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** 처리할 WAITING 하나를 처리하고, 처리하거나 실패를 기록한 이벤트가 있었는지 반환한다. 선점이나 실패 기록 자체가 실패하면 예외를 그대로 던진다. */
  public boolean processNext() {
    return processClaimed(inbox::claimNextWaiting);
  }

  /** 지정한 이벤트만 처리한다. 개발 시나리오 재개가 다른 WAITING 작업을 소비하지 않게 한다. */
  public boolean processEvent(UUID eventId) {
    return processClaimed(now -> inbox.claimWaitingEvent(eventId, now));
  }

  private boolean processClaimed(Function<Instant, Optional<WaitingInboxEvent>> claim) {
    AtomicReference<WaitingInboxEvent> claimed = new AtomicReference<>();
    try {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                Instant now = clock.instant();
                Optional<WaitingInboxEvent> next = claim.apply(now);
                next.ifPresent(claimed::set);
                return process(next, now);
              }));
    } catch (UnreadableInboxEventException exception) {
      // 다시 읽어도 같은 결과이므로 재시도하지 않는다.
      recordFailure(
          exception.inboxId(),
          null,
          exception.version(),
          exception.attempts(),
          BODY_UNREADABLE,
          false,
          exception);
      return true;
    } catch (RuntimeException exception) {
      WaitingInboxEvent failed = claimed.get();
      if (failed == null) {
        throw exception;
      }
      recordFailure(
          failed.inboxId(),
          failed.event().eventId(),
          failed.version(),
          failed.attempts(),
          PROCESSING_FAILED,
          true,
          exception);
      return true;
    }
  }

  /**
   * 롤백된 처리의 실패를 새 트랜잭션에서 기록한다. 선점 때 본 횟수에 이번 실패를 더해 상한에 닿았거나 재시도할 수 없는 실패면 REVIEW_REQUIRED, 아니면 대기
   * 뒤 다시 처리할 WAITING으로 남긴다.
   */
  private void recordFailure(
      UUID inboxId,
      UUID eventId,
      long version,
      int previousAttempts,
      String errorCode,
      boolean retryable,
      RuntimeException cause) {
    int attempts = previousAttempts + 1;
    Instant now = clock.instant();
    Instant retryAt = retryable && attempts < MAX_ATTEMPTS ? now.plus(retryDelay(attempts)) : null;
    Boolean recorded;
    try {
      recorded =
          transactions.execute(
              status -> inbox.recordFailure(inboxId, version, errorCode, retryAt, now));
    } catch (RuntimeException recordFailure) {
      // 기록 실패만 올라가면 처리 실패의 원인을 잃으므로 함께 싣는다.
      recordFailure.addSuppressed(cause);
      throw recordFailure;
    }
    String result =
        !Boolean.TRUE.equals(recorded)
            ? "SKIPPED"
            : retryAt == null ? "REVIEW_REQUIRED" : "WAITING";
    LOG.atWarn()
        .setCause(cause)
        .addKeyValue("event", "payment.accept")
        .addKeyValue("inboxId", inboxId)
        .addKeyValue("eventId", eventId)
        .addKeyValue("attempts", attempts)
        .addKeyValue("result", result)
        .addKeyValue("errorCode", errorCode)
        .log("Payment request processing failed");
  }

  /** n번째 실패 뒤 대기 시간. 1초부터 두 배씩 늘리고 60초를 넘지 않는다. */
  static Duration retryDelay(int attempts) {
    return Duration.ofSeconds(Math.min(60, 1L << Math.min(attempts - 1, 6)));
  }

  private boolean process(Optional<WaitingInboxEvent> claimed, Instant now) {
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

    long mileage = amounts.get().mileageAmount();
    Optional<MileageWallet> wallet = Optional.empty();
    if (mileage > 0) {
      // 같은 구매자의 다른 처리는 이 트랜잭션이 끝날 때까지 기다린 뒤 줄어든 가용 잔액을 본다.
      wallet = wallets.findByBuyerIdForUpdate(payload.buyerId());
      if (wallet.isEmpty() || wallet.get().available() < mileage) {
        return review(claimed.get(), "PAYMENT_REQUEST_MILEAGE_INSUFFICIENT", now);
      }
    }

    Payment payment = createPayment(payload, amounts.get(), now);
    if (mileage > 0) {
      reserveMileage(wallet.get(), payment, now);
    }
    appendAccepted(payment, payload, now);
    inbox.markApplied(inboxId, payment.getId(), now);
    return true;
  }

  /**
   * 결제의 사용 마일리지를 예약하고 원장에 남긴다. 외부 결제액이 0원이면 PG 승인이 필요 없으므로 같은 트랜잭션에서 바로 차감하고 결제를 확정한다. 차감 원장은 지갑에서
   * 차감한 뒤 기록해야 차감 후 잔액이 남는다.
   */
  private void reserveMileage(MileageWallet wallet, Payment payment, Instant now) {
    wallet.reserve(payment.getAmounts().mileageAmount(), now);
    MileageReservation reservation =
        reservations.save(MileageReservation.forPayment(wallet, payment, now));
    entries.save(MileageEntry.reserve(wallet, reservation, now));

    if (payment.getAmounts().externalAmount() == 0) {
      wallet.consumeReserved(reservation.getAmount(), now);
      reservation.consume(now);
      entries.save(MileageEntry.debit(wallet, reservation, now));
      payment.confirm(now);
    }
    wallets.save(wallet);
  }

  /** 새 결제를 만든 같은 트랜잭션에서 PaymentAccepted를 Outbox에 남긴다. 기존 결제를 돌려주는 재요청에서는 호출하지 않는다. */
  private void appendAccepted(Payment payment, OrderPaymentRequestedPayload request, Instant now) {
    PaymentAcceptedPayload payload =
        new PaymentAcceptedPayload(
            payment.getId(),
            request.orderId(),
            request.orderVersion(),
            payment.getCurrentCycleId());
    // 결제 ID에서 고정 eventId를 만들어 같은 결제의 중복 append를 Outbox가 한 건으로 합치게 한다.
    EventEnvelope<PaymentAcceptedPayload> envelope =
        new EventEnvelope<>(
            UUID.nameUUIDFromBytes(
                ("PaymentAccepted:" + payment.getId()).getBytes(StandardCharsets.UTF_8)),
            "PaymentAccepted",
            1,
            now.atOffset(ZoneOffset.UTC),
            payment.getId(),
            0,
            payload);
    outbox.appendPaymentAccepted(envelope);
  }

  /** 거절 사유와 함께 확인 대상으로 남긴다. 결제수단 참조 같은 payload 원문은 로그에 남기지 않는다. */
  private boolean review(WaitingInboxEvent claimed, String errorCode, Instant now) {
    inbox.markReviewRequired(claimed.inboxId(), errorCode, now);
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
