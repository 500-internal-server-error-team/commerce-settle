package com.commercesettle.payment.application.service;

import com.commercesettle.observability.RepeatedWarning;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentAcceptedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationRequestedPayload;
import com.commercesettle.payment.application.event.PaymentCancellationResultPayload;
import com.commercesettle.payment.application.event.PaymentConfirmedPayload;
import com.commercesettle.payment.application.port.InboxClaim;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.application.port.PaymentProcessingObservation;
import com.commercesettle.payment.application.port.PaymentProcessingObservation.Queue;
import com.commercesettle.payment.application.port.PaymentProcessingObservation.Stage;
import com.commercesettle.payment.application.port.WaitingInboxEvent;
import com.commercesettle.payment.domain.exception.InvalidPaymentAmountException;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageReservation;
import com.commercesettle.payment.domain.model.MileageReservationStatus;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAmounts;
import com.commercesettle.payment.domain.model.PaymentJob;
import com.commercesettle.payment.domain.model.PaymentJobOperation;
import com.commercesettle.payment.domain.model.PaymentStatus;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * WAITING Inbox의 결제 요청을 검사해 결제·마일리지 예약·승인 작업을 만들고 처리 결과를 기록한다. 마일리지 전액 결제는 같은 트랜잭션에서 차감·확정한다. 환불
 * 승인·주문 복구 확인과 Settlement 환불 응답은 {@link RefundEventHandler}, 구매 적립 예정·확정은 {@link
 * MileageAccrualEventHandler}가 같은 선점·실패 기록 규칙 아래 반영한다.
 *
 * <p>선점은 별도 트랜잭션으로 커밋해 시도 횟수를 늘리고 다음 처리 시각을 임대 만료로 미룬다. 그래서 처리가 롤백된 뒤 실패를 기록하기 전에도 다른 처리기가 같은 행을 다시
 * 잡지 않으며, 기록 전에 중단돼도 그 시도는 횟수에 남는다. 재시도해도 결과가 같은 업무상 거절은 예외 없이 사유와 함께 REVIEW_REQUIRED로 남긴다. 그 밖의
 * 예외는 처리 트랜잭션을 롤백한 뒤 사유를 기록하고 1초부터 두 배씩 최대 60초 뒤에 다시 처리한다. 10번째 시도가 실패하면 REVIEW_REQUIRED로 남긴다.
 */
@Service
public class ApplyOrderPaymentRequestService {
  static final int MAX_ATTEMPTS = 10;
  static final String PROCESSING_FAILED = "PAYMENT_REQUEST_PROCESSING_FAILED";
  static final String PAYMENT_CANCELLED = "PaymentCancelled";
  static final String PAYMENT_CANCELLATION_REJECTED = "PaymentCancellationRejected";
  static final String CANCELLED_BEFORE_APPROVAL = "CANCELLED_BEFORE_APPROVAL";
  static final String CANCELLED_BEFORE_PAYMENT = "CANCELLED_BEFORE_PAYMENT";
  static final String PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS";
  static final String PAYMENT_ALREADY_CONFIRMED = "PAYMENT_ALREADY_CONFIRMED";
  static final String PAYMENT_CYCLE_MISMATCH = "PAYMENT_CYCLE_MISMATCH";
  static final String PAYMENT_CANCELLATION_REQUEST_INVALID = "PAYMENT_CANCELLATION_REQUEST_INVALID";
  private static final String PAYMENT_CANCELLED_JOB = "PAYMENT_CANCELLED";

  private static final Logger LOG = LoggerFactory.getLogger(ApplyOrderPaymentRequestService.class);
  private final RepeatedWarning processingRetry = new RepeatedWarning();

  private final InboxStore inbox;
  private final PaymentRepository payments;
  private final PaymentJobRepository jobs;
  private final MileageWalletRepository wallets;
  private final MileageReservationRepository reservations;
  private final MileageEntryRepository entries;
  private final PaymentEventOutbox outbox;
  private final RefundEventHandler refundEvents;
  private final MileageAccrualEventHandler accrualEvents;
  private final PaymentNotices notices;
  private final TransactionOperations transactions;
  private final Clock clock;
  private final Duration lease;
  private final PaymentProcessingObservation observations;

  public ApplyOrderPaymentRequestService(
      InboxStore inbox,
      PaymentRepository payments,
      PaymentJobRepository jobs,
      MileageWalletRepository wallets,
      MileageReservationRepository reservations,
      MileageEntryRepository entries,
      PaymentEventOutbox outbox,
      RefundEventHandler refundEvents,
      MileageAccrualEventHandler accrualEvents,
      PaymentNotices notices,
      TransactionOperations transactions,
      Clock clock,
      @Value("${app.payment-request-processor.lease-ms:30000}") long leaseMs,
      PaymentProcessingObservation observations) {
    if (leaseMs <= 0) {
      throw new IllegalArgumentException("Invalid payment request lease");
    }
    this.inbox = inbox;
    this.payments = payments;
    this.jobs = jobs;
    this.wallets = wallets;
    this.reservations = reservations;
    this.entries = entries;
    this.outbox = outbox;
    this.refundEvents = refundEvents;
    this.accrualEvents = accrualEvents;
    this.notices = notices;
    this.transactions = transactions;
    this.clock = clock;
    this.lease = Duration.ofMillis(leaseMs);
    this.observations = observations;
  }

  /**
   * 처리할 WAITING 하나를 처리하고, 다룬 행이 있었는지 반환한다. 처리, 실패 기록, 확인 대상 전환 모두 1건이다. 선점이나 실패 기록 자체가 실패하면 예외를 그대로
   * 던진다.
   */
  public boolean processNext() {
    return processClaimed(now -> inbox.claimNextWaiting(now, now.plus(lease), MAX_ATTEMPTS));
  }

  /** 지정한 이벤트만 처리한다. 개발 시나리오 재개가 다른 WAITING 작업을 소비하지 않게 한다. */
  public boolean processEvent(UUID eventId) {
    return processClaimed(
        now -> inbox.claimWaitingEvent(eventId, now, now.plus(lease), MAX_ATTEMPTS));
  }

  private boolean processClaimed(Function<Instant, Optional<InboxClaim>> claim) {

    Optional<InboxClaim> next =
        observations.observe(
            Stage.INBOX_CLAIM, () -> transactions.execute(status -> claim.apply(clock.instant())));
    if (next == null || next.isEmpty()) {
      return false;
    }
    if (!(next.get() instanceof InboxClaim.Claimed(WaitingInboxEvent claimed))) {
      // 선점 트랜잭션에서 이미 확인 대상으로 넘기고 커밋했다. 처리 1건으로 센다.
      return true;
    }
    return observations.observeStored(
        Stage.INBOX_PROCESS,
        claimed.traceParent(),
        Map.of(
            "event.id",
            claimed.event().eventId().toString(),
            "inbox.id",
            claimed.inboxId().toString()),
        claimed.timing(),
        () -> processObserved(claimed));
  }

  private boolean processObserved(WaitingInboxEvent claimed) {
    observations.identify("event.id", claimed.event().eventId());
    observations.identify("inbox.id", claimed.inboxId());
    observations.queueWait(
        Queue.INBOX,
        claimed.timing() == null ? claimed.registeredAt() : claimed.timing().initialAt(),
        claimed.attempts());
    try {
      observations.observe(
          Stage.INBOX_APPLY,
          () ->
              transactions.executeWithoutResult(
                  status -> {
                    inbox.lockOrder(claimed.lockKey());
                    if (!inbox.lockClaimed(claimed.inboxId(), claimed.version())) {
                      // 취소 처리에서 이미 완료했거나 다른 처리기가 다시 선점했다. 기존 결과를 덮어쓰지 않는다.
                      LOG.atDebug()
                          .addKeyValue("event", "payment.accept")
                          .addKeyValue("inboxId", claimed.inboxId())
                          .addKeyValue("result", "SKIPPED")
                          .log("Payment request already handled or lease lost");
                      return;
                    }
                    process(Optional.of(claimed), clock.instant());
                  }));
    } catch (RuntimeException exception) {
      recordFailure(claimed, exception);
    }
    return true;
  }

  /**
   * 롤백된 처리의 실패를 새 트랜잭션에서 기록한다. 선점 때 늘린 시도 횟수가 상한에 닿았으면 REVIEW_REQUIRED, 아니면 대기 뒤 다시 처리할 WAITING으로
   * 남긴다.
   */
  private void recordFailure(WaitingInboxEvent claimed, RuntimeException cause) {
    int attempts = claimed.attempts();
    Instant now = clock.instant();
    Instant retry =
        attempts < MAX_ATTEMPTS ? now.plus(PaymentRetryPolicy.delayAfter(attempts)) : null;
    Boolean recorded;
    try {
      recorded =
          observations.observe(
              Stage.INBOX_FAILURE,
              () ->
                  transactions.execute(
                      status ->
                          inbox.recordFailure(
                              claimed.inboxId(),
                              claimed.version(),
                              PROCESSING_FAILED,
                              retry,
                              now)));
    } catch (RuntimeException recordFailure) {
      // 기록 실패만 올라가면 처리 실패의 원인을 잃으므로 함께 싣는다.
      recordFailure.addSuppressed(cause);
      throw recordFailure;
    }
    String result =
        !Boolean.TRUE.equals(recorded) ? "SKIPPED" : retry == null ? "REVIEW_REQUIRED" : "WAITING";
    // 다시 처리할 실패는 장애 중 건마다 반복되므로 묶어서 남기고, 확인 대상 전환만 매번 남긴다.
    (result.equals("REVIEW_REQUIRED") ? LOG.atWarn() : processingRetry.at(LOG))
        .setCause(cause)
        .addKeyValue("event", "payment.accept")
        .addKeyValue("inboxId", claimed.inboxId())
        .addKeyValue("eventId", claimed.event().eventId())
        .addKeyValue("attempts", attempts)
        .addKeyValue("result", result)
        .addKeyValue("errorCode", PROCESSING_FAILED)
        .log("Payment request processing failed");
  }

  private boolean process(Optional<WaitingInboxEvent> claimed, Instant now) {
    if (claimed.isEmpty()) {
      return false;
    }
    UUID inboxId = claimed.get().inboxId();
    if (refundEvents.handles(claimed.get().event())) {
      refundEvents.handle(claimed.get(), now);
      return true;
    }
    if (accrualEvents.handles(claimed.get().event())) {
      accrualEvents.handle(claimed.get(), now);
      return true;
    }
    if (claimed.get().event().payload()
        instanceof PaymentCancellationRequestedPayload cancellation) {
      return cancel(claimed.get(), cancellation, now);
    }
    OrderPaymentRequestedPayload payload =
        (OrderPaymentRequestedPayload) claimed.get().event().payload();

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
      Payment payment = payments.findByIdForUpdate(existing.get().getId()).orElseThrow();
      observations.identify("payment.id", payment.getId());
      if (isSameRequest(payment, payload, amounts.get())) {
        inbox.markApplied(inboxId, payment.getId(), now);
        return true;
      }
      if (isRetryRequest(payment, payload, amounts.get())) {
        return retry(claimed.get(), payment, payload, now);
      }
      return review(claimed.get(), "PAYMENT_REQUEST_ORDER_CONFLICT", now);
    }

    Optional<UUID> earlyCancellation =
        inbox.findEarlyCancellation(payload.orderId(), payload.paymentCycleId());
    if (earlyCancellation.isPresent()) {
      // 취소가 먼저 처리됐다. 늦은 결제 요청으로 결제를 살리지 않고 취소 상태로 만든다. 승인 작업·마일리지 예약은 만들지 않는다.
      Payment payment = payments.save(newPayment(payload, amounts.get(), now));
      observations.identify("payment.id", payment.getId());
      payment.cancel(now);
      appendAccepted(payment, payload, now);
      appendCancellationResult(
          PAYMENT_CANCELLED, payment, CANCELLED_BEFORE_PAYMENT, cancelledEventId(payment), 0, now);
      inbox.markApplied(inboxId, payment.getId(), now);
      inbox.linkResult(earlyCancellation.get(), payment.getId(), now);
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
    observations.identify("payment.id", payment.getId());
    if (mileage > 0) {
      reserveMileage(wallet.get(), payment, now);
    }
    appendAccepted(payment, payload, now);
    if (payment.getStatus() == PaymentStatus.CONFIRMED) {
      // 마일리지 전액 결제는 외부 승인 없이 같은 트랜잭션에서 확정했다. 새 결제라 버전은 0이고 PaymentAccepted 뒤에 발행된다.
      outbox.appendPaymentConfirmed(
          new EventEnvelope<>(
              UUID.nameUUIDFromBytes(
                  ("PaymentConfirmed:" + payment.getId()).getBytes(StandardCharsets.UTF_8)),
              "PaymentConfirmed",
              1,
              now.atOffset(ZoneOffset.UTC),
              payment.getId(),
              0,
              PaymentConfirmedPayload.of(payment)));
      notices.completed(payment, null, wallet.orElseThrow(), now, 0);
    }
    inbox.markApplied(inboxId, payment.getId(), now);
    return true;
  }

  private boolean retry(
      WaitingInboxEvent claimed,
      Payment payment,
      OrderPaymentRequestedPayload request,
      Instant now) {
    if (!previousMileageWasReleased(payment)) {
      return review(claimed, "PAYMENT_RETRY_RESERVATION_CONFLICT", now);
    }
    Optional<UUID> cancellation =
        inbox.findEarlyCancellation(request.orderId(), request.paymentCycleId());
    if (cancellation.isPresent()) {
      cancelRetry(payment, request, now);
      inbox.linkResult(cancellation.get(), payment.getId(), now);
      inbox.markApplied(claimed.inboxId(), payment.getId(), now);
      return true;
    }
    long mileage = payment.getAmounts().mileageAmount();
    Optional<MileageWallet> wallet = Optional.empty();
    if (mileage > 0) {
      wallet = wallets.findByBuyerIdForUpdate(payment.getBuyerId());
      if (wallet.isEmpty() || wallet.get().available() < mileage) {
        return review(claimed, "PAYMENT_REQUEST_MILEAGE_INSUFFICIENT", now);
      }
    }
    long version = Math.addExact(Objects.requireNonNullElse(payment.getVersion(), 0L), 1);
    payment.acceptRetryCycle(request.paymentCycleId(), request.orderVersion(), now);
    payments.save(payment);
    if (mileage > 0) reserveMileage(wallet.orElseThrow(), payment, now);
    jobs.save(PaymentJob.approvalFor(payment, now));
    appendAccepted(payment, request, now, version);
    inbox.markApplied(claimed.inboxId(), payment.getId(), now);
    return true;
  }

  private boolean previousMileageWasReleased(Payment payment) {
    Optional<MileageReservation> previous = reservations.findByPaymentId(payment.getId());
    if (payment.getAmounts().mileageAmount() == 0) return previous.isEmpty();
    return previous
        .filter(row -> row.getCycleId().equals(payment.getCurrentCycleId()))
        .filter(row -> row.getAmount() == payment.getAmounts().mileageAmount())
        .filter(row -> row.getStatus() == MileageReservationStatus.RELEASED)
        .isPresent();
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
    appendAccepted(payment, request, now, 0);
  }

  private void appendAccepted(
      Payment payment, OrderPaymentRequestedPayload request, Instant now, long version) {
    PaymentAcceptedPayload payload =
        new PaymentAcceptedPayload(
            payment.getId(),
            request.orderId(),
            request.orderVersion(),
            payment.getCurrentCycleId());
    // 같은 회차는 같은 ID, 새 회차는 다른 ID로 원문·멱등 이력을 보존한다.
    EventEnvelope<PaymentAcceptedPayload> envelope =
        new EventEnvelope<>(
            UUID.nameUUIDFromBytes(
                ("PaymentAccepted:" + payment.getId() + ":" + payment.getCurrentCycleId())
                    .getBytes(StandardCharsets.UTF_8)),
            "PaymentAccepted",
            1,
            now.atOffset(ZoneOffset.UTC),
            payment.getId(),
            version,
            payload);
    outbox.appendPaymentAccepted(envelope);
  }

  /** 거절 사유와 함께 확인 대상으로 남긴다. 결제수단 참조 같은 payload 원문은 로그에 남기지 않는다. */
  private boolean review(WaitingInboxEvent claimed, String errorCode, Instant now) {
    inbox.markReviewRequired(claimed.inboxId(), errorCode, now);
    LOG.atWarn()
        .addKeyValue("event", "payment.accept")
        .addKeyValue("eventId", claimed.event().eventId())
        .addKeyValue("orderId", claimed.event().aggregateId())
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
    return existing.getCurrentCycleId().equals(payload.paymentCycleId())
        && existing.getOrderVersion() == payload.orderVersion()
        && isSameAgreement(existing, payload, amounts);
  }

  private boolean isRetryRequest(
      Payment existing, OrderPaymentRequestedPayload payload, PaymentAmounts amounts) {
    return existing.getStatus() == PaymentStatus.DECLINED
        && existing.getAmounts().externalAmount() > 0
        && !existing.getCurrentCycleId().equals(payload.paymentCycleId())
        && payload.orderVersion() > existing.getOrderVersion()
        && jobs.findByOperationAndBusinessKey(PaymentJobOperation.APPROVE, payload.paymentCycleId())
            .isEmpty()
        && isSameAgreement(existing, payload, amounts);
  }

  private boolean isSameAgreement(
      Payment existing, OrderPaymentRequestedPayload payload, PaymentAmounts amounts) {
    boolean samePayment =
        existing.getBuyerId().equals(payload.buyerId())
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
    Payment payment = payments.save(newPayment(payload, amounts, now));
    if (amounts.externalAmount() > 0) {
      jobs.save(PaymentJob.approvalFor(payment, now));
    }
    return payment;
  }

  private static Payment newPayment(
      OrderPaymentRequestedPayload payload, PaymentAmounts amounts, Instant now) {
    return Payment.create(
        payload.orderId(),
        payload.orderVersion(),
        payload.paymentCycleId(),
        payload.buyerId(),
        payload.sellerId(),
        payload.productId(),
        amounts,
        payload.policyVersion(),
        now);
  }

  /**
   * 미결제 취소 요청을 처리한다. 외부 승인을 시작하기 전(PENDING)이거나 확정 거절된 결제만 취소하고, 승인 중·미확정·확정된 결제는 원거래 결과를 끝까지 확인하도록
   * 취소를 거절한다. 결제가 아직 없으면 취소를 먼저 처리 완료로 남겨 늦게 온 결제 요청이 결제를 취소 상태로 만들게 한다.
   */
  private boolean cancel(
      WaitingInboxEvent claimed, PaymentCancellationRequestedPayload request, Instant now) {
    Optional<Payment> found = payments.findByOrderId(request.orderId());
    if (found.isEmpty()) {
      return cancelWithoutPayment(claimed, request, now);
    }
    Payment payment = payments.findByIdForUpdate(found.get().getId()).orElseThrow();
    // 저장된 결제는 항상 버전이 있다. 저장 전 결제는 처음 저장될 때 0이 된다.
    long version = Objects.requireNonNullElse(payment.getVersion(), 0L);
    if (!payment.getCurrentCycleId().equals(request.paymentCycleId())) {
      if (payment.getStatus() == PaymentStatus.DECLINED
          && request.orderVersion() > payment.getOrderVersion()) {
        return cancelFutureRetry(claimed, payment, request, now);
      }
      reject(claimed, payment, PAYMENT_CYCLE_MISMATCH, version, now);
    } else {
      switch (payment.getStatus()) {
        case PENDING, DECLINED -> {
          payment.cancel(now);
          releaseMileage(payment, now);
          jobs.findByOperationAndBusinessKey(
                  PaymentJobOperation.APPROVE, payment.getCurrentCycleId())
              .filter(job -> job.closeBeforeRun(PAYMENT_CANCELLED_JOB, now))
              .ifPresent(jobs::save);
          payments.save(payment);
          // 취소로 결제 버전이 하나 오른다.
          appendCancellationResult(
              PAYMENT_CANCELLED,
              payment,
              CANCELLED_BEFORE_APPROVAL,
              cancelledEventId(payment),
              version + 1,
              now);
        }
        case CANCELLED -> {
          // 이미 취소했고 PaymentCancelled도 남겼다. 같은 결과를 다시 만들지 않는다.
        }
        case PROCESSING, UNKNOWN -> reject(claimed, payment, PAYMENT_IN_PROGRESS, version, now);
        case CONFIRMED -> reject(claimed, payment, PAYMENT_ALREADY_CONFIRMED, version, now);
      }
    }
    inbox.markApplied(claimed.inboxId(), payment.getId(), now);
    return true;
  }

  private boolean cancelFutureRetry(
      WaitingInboxEvent claimed,
      Payment payment,
      PaymentCancellationRequestedPayload request,
      Instant now) {
    Optional<InboxStore.PaymentRequest> original =
        inbox.findPaymentRequest(request.orderId(), request.paymentCycleId());
    if (original.isEmpty()) {
      // 새 회차의 원문이 아직 없으면 채택·승인하지 않고 선행 취소로 보존한다.
      inbox.markApplied(claimed.inboxId(), null, now);
      return true;
    }
    var payload = original.get().payload();
    Optional<PaymentAmounts> amounts = toAmounts(payload);
    if (payload.status() != OrderPaymentRequestedPayload.Status.PAYMENT_PENDING
        || amounts.isEmpty()
        || !matchesCommerceAmounts(payload, amounts.get())
        || !isRetryRequest(payment, payload, amounts.get())
        || payload.orderVersion() > request.orderVersion()
        || !previousMileageWasReleased(payment)) {
      return review(claimed, PAYMENT_CANCELLATION_REQUEST_INVALID, now);
    }
    cancelRetry(payment, payload, now);
    inbox.linkPaymentRequest(original.get().inboxId(), payment.getId(), now);
    inbox.markApplied(claimed.inboxId(), payment.getId(), now);
    return true;
  }

  private void cancelRetry(Payment payment, OrderPaymentRequestedPayload request, Instant now) {
    long version = Math.addExact(Objects.requireNonNullElse(payment.getVersion(), 0L), 1);
    payment.acceptRetryCycle(request.paymentCycleId(), request.orderVersion(), now);
    payment.cancel(now);
    payments.save(payment);
    appendAccepted(payment, request, now, version);
    appendCancellationResult(
        PAYMENT_CANCELLED,
        payment,
        CANCELLED_BEFORE_PAYMENT,
        cancelledEventId(payment),
        version,
        now);
  }

  /**
   * 결제가 아직 없는 주문의 취소. 같은 주문·회차의 결제 요청 원문을 이미 받았다면(처리 대기든 확인 대상이든) 그 원문으로 승인 작업·마일리지 예약 없이 취소된 결제를
   * 만들고 결과를 남긴다. 원문이 확인 대상으로 끝나 다시 처리되지 않아도 취소가 확정된다. 원문이 아직 없으면 취소를 결과 참조 없이 APPLIED로 남기고, 늦게 온 결제
   * 요청이 취소된 결제를 만든다. 원문 금액이 규칙에 맞지 않아 결제를 만들 수 없으면 취소도 확인 대상으로 남긴다.
   */
  private boolean cancelWithoutPayment(
      WaitingInboxEvent claimed, PaymentCancellationRequestedPayload request, Instant now) {
    Optional<InboxStore.PaymentRequest> original =
        inbox.findPaymentRequest(request.orderId(), request.paymentCycleId());
    if (original.isEmpty()) {
      inbox.markApplied(claimed.inboxId(), null, now);
      return true;
    }
    OrderPaymentRequestedPayload payload = original.get().payload();
    Optional<PaymentAmounts> amounts = toAmounts(payload);
    if (payload.status() != OrderPaymentRequestedPayload.Status.PAYMENT_PENDING
        || amounts.isEmpty()
        || !matchesCommerceAmounts(payload, amounts.get())) {
      return review(claimed, PAYMENT_CANCELLATION_REQUEST_INVALID, now);
    }
    Payment payment = payments.save(newPayment(payload, amounts.get(), now));
    payment.cancel(now);
    inbox.linkPaymentRequest(original.get().inboxId(), payment.getId(), now);
    appendAccepted(payment, payload, now);
    appendCancellationResult(
        PAYMENT_CANCELLED, payment, CANCELLED_BEFORE_PAYMENT, cancelledEventId(payment), 0, now);
    inbox.markApplied(claimed.inboxId(), payment.getId(), now);
    return true;
  }

  /** 차감하지 않은 마일리지 예약이 있으면 풀어 준다. 지갑을 잠가 같은 구매자의 다른 처리와 직렬화한다. */
  private void releaseMileage(Payment payment, Instant now) {
    Optional<MileageReservation> reservation = reservations.findByPaymentId(payment.getId());
    if (reservation.isEmpty()
        || reservation.get().getStatus() != MileageReservationStatus.RESERVED) {
      return;
    }
    MileageWallet wallet =
        wallets
            .findByBuyerIdForUpdate(payment.getBuyerId())
            .orElseThrow(() -> new IllegalStateException("Reserved mileage wallet is missing"));
    wallet.releaseReserved(reservation.get().getAmount(), now);
    reservation.get().release(now);
    reservations.save(reservation.get());
    entries.save(MileageEntry.release(wallet, reservation.get(), now));
    wallets.save(wallet);
  }

  private void reject(
      WaitingInboxEvent claimed, Payment payment, String reasonCode, long version, Instant now) {
    // 취소 요청마다 거절 결과를 하나씩 남긴다.
    UUID eventId =
        UUID.nameUUIDFromBytes(
            (PAYMENT_CANCELLATION_REJECTED + ":" + claimed.event().eventId())
                .getBytes(StandardCharsets.UTF_8));
    appendCancellationResult(
        PAYMENT_CANCELLATION_REJECTED, payment, reasonCode, eventId, version, now);
  }

  private static UUID cancelledEventId(Payment payment) {
    return UUID.nameUUIDFromBytes(
        (PAYMENT_CANCELLED + ":" + payment.getId()).getBytes(StandardCharsets.UTF_8));
  }

  private void appendCancellationResult(
      String eventType,
      Payment payment,
      String reasonCode,
      UUID eventId,
      long aggregateVersion,
      Instant now) {
    outbox.appendCancellationResult(
        new EventEnvelope<>(
            eventId,
            eventType,
            1,
            now.atOffset(ZoneOffset.UTC),
            payment.getId(),
            aggregateVersion,
            new PaymentCancellationResultPayload(
                payment.getId(), payment.getOrderId(), payment.getCurrentCycleId(), reasonCode)));
  }
}
