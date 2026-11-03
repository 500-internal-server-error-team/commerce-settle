package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.ApprovalWorkerSettings;
import com.commercesettle.payment.application.event.EventEnvelope;
import com.commercesettle.payment.application.event.OrderPaymentRequestedPayload;
import com.commercesettle.payment.application.event.PaymentConfirmedPayload;
import com.commercesettle.payment.application.event.PaymentDeclinedPayload;
import com.commercesettle.payment.application.port.InboxStore;
import com.commercesettle.payment.application.port.PaymentEventOutbox;
import com.commercesettle.payment.application.port.PaymentGateway.ApprovalRequest;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageReservation;
import com.commercesettle.payment.domain.model.MileageReservationStatus;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAttempt;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.domain.model.PaymentStatus;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageReservationRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentAttemptRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 Worker의 로컬 트랜잭션 단계. 대역 호출 전후로 나눠 외부 호출 중에는 DB 트랜잭션을 열어 두지 않는다. 결과 반영은 유효한 임대에서만 하며, 임대를 잃었으면
 * 결제·시도·마일리지·Outbox 변경을 모두 롤백한다. 준비와 결과 반영은 결제 행을 대기 상한 안에서 먼저 잠그므로, 미결제 취소 등과 경합이 길면 임대가 만료되기 전에
 * 예외로 롤백한다.
 */
@Component
class ApprovalResultRecorder {
  static final String DECLINED_BY_PROVIDER = "DECLINED_BY_PROVIDER";
  private static final Logger LOG = LoggerFactory.getLogger(ApprovalResultRecorder.class);

  private final PaymentJobRepository jobs;
  private final PaymentRepository payments;
  private final PaymentAttemptRepository attempts;
  private final InboxStore inbox;
  private final MileageWalletRepository wallets;
  private final MileageReservationRepository reservations;
  private final MileageEntryRepository entries;
  private final PaymentEventOutbox outbox;
  private final PaymentNotices notices;
  private final ApprovalWorkerSettings settings;
  private final Clock clock;

  ApprovalResultRecorder(
      PaymentJobRepository jobs,
      PaymentRepository payments,
      PaymentAttemptRepository attempts,
      InboxStore inbox,
      MileageWalletRepository wallets,
      MileageReservationRepository reservations,
      MileageEntryRepository entries,
      PaymentEventOutbox outbox,
      PaymentNotices notices,
      ApprovalWorkerSettings settings,
      Clock clock) {
    this.jobs = jobs;
    this.payments = payments;
    this.attempts = attempts;
    this.inbox = inbox;
    this.wallets = wallets;
    this.reservations = reservations;
    this.entries = entries;
    this.outbox = outbox;
    this.notices = notices;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * 호출할 요청을 준비한다. 처음 실행하는 결제(PENDING)는 승인 시도를 만들고 PROCESSING으로 바꿔 실행(POST)을, 이미 처리 중이거나 미확정이면 결과를
   * 모르므로 원거래 조회부터 하게 한다. 취소·확정·거절된 결제는 호출하지 않고 작업을 끝낸다. 임대에 호출 제한 시간만큼 남지 않았거나 시도 상한을 넘었으면 호출하지
   * 않는다.
   */
  @Transactional
  public Optional<Prepared> prepare(LeasedJob job) {
    Payment payment = payments.findByIdForUpdate(job.paymentId()).orElseThrow();
    // 잠금을 기다리는 사이 임대가 만료됐을 수 있어 잠근 뒤 확인한다.
    if (!jobs.holdsLease(job.jobId(), job.leaseToken(), settings.httpTimeout())) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (job.attempts() > settings.maxAttempts()) {
      exhaust(job, payment, now);
      return Optional.empty();
    }
    switch (payment.getStatus()) {
      case PENDING -> {
        Optional<OrderPaymentRequestedPayload> original =
            inbox.findOriginalRequest(payment.getId());
        if (original.isEmpty() || original.get().externalMethod() == null) {
          release(job, PaymentJobStatus.REVIEW_REQUIRED, "PAYMENT_ORIGINAL_REQUEST_MISSING");
          return Optional.empty();
        }
        String method = original.get().externalMethod().name();
        PaymentAttempt attempt =
            attempts.save(
                PaymentAttempt.start(
                    payment,
                    "EASY_PAY".equals(method) ? "EASY_PAY" : settings.cardProvider(),
                    method,
                    original.get().methodAccountRef(),
                    settings.fundingAccountRef(),
                    settings.scenarioVersion(),
                    now));
        payment.startProcessing(now);
        payments.save(payment);
        return Optional.of(new Prepared(request(attempt), true));
      }
      case PROCESSING, UNKNOWN -> {
        PaymentAttempt attempt =
            attempts
                .findByPaymentIdAndCycleId(payment.getId(), payment.getCurrentCycleId())
                .orElseThrow();
        return Optional.of(new Prepared(request(attempt), false));
      }
      default -> {
        // 외부 호출 전에 취소됐거나 이미 결과가 반영됐다. 호출하지 않고 작업만 끝낸다.
        release(job, PaymentJobStatus.DONE, null);
        return Optional.empty();
      }
    }
  }

  @Transactional
  public void record(LeasedJob job, Result result) {
    Instant now = clock.instant();
    // 결제 행을 대기 상한 안에서 잠가, 경합이 길면 임대가 만료되기 전에 롤백한다.
    Payment payment = payments.findByIdForUpdate(job.paymentId()).orElseThrow();
    PaymentAttempt attempt =
        attempts
            .findByPaymentIdAndCycleId(payment.getId(), payment.getCurrentCycleId())
            .orElseThrow();
    long version = Objects.requireNonNullElse(payment.getVersion(), 0L);
    switch (result) {
      case Result.Succeeded succeeded -> {
        release(job, PaymentJobStatus.DONE, null);
        attempt.approve(succeeded.externalRef(), now);
        MileageWallet wallet = consumeMileage(payment, now);
        payment.confirm(now);
        payments.save(payment);
        outbox.appendPaymentConfirmed(
            new EventEnvelope<>(
                eventId("PaymentConfirmed", payment),
                "PaymentConfirmed",
                1,
                now.atOffset(ZoneOffset.UTC),
                payment.getId(),
                version + 1,
                PaymentConfirmedPayload.of(payment)));
        notices.completed(payment, null, wallet, now, version + 1);
      }
      case Result.Declined declined -> {
        release(job, PaymentJobStatus.DONE, null);
        attempt.decline(declined.externalRef(), now);
        releaseMileage(payment, now);
        payment.decline(now);
        payments.save(payment);
        outbox.appendPaymentDeclined(
            new EventEnvelope<>(
                eventId("PaymentDeclined", payment),
                "PaymentDeclined",
                1,
                now.atOffset(ZoneOffset.UTC),
                payment.getId(),
                version + 1,
                new PaymentDeclinedPayload(
                    payment.getId(),
                    payment.getOrderId(),
                    payment.getCurrentCycleId(),
                    DECLINED_BY_PROVIDER)));
      }
      case Result.Rejected rejected -> {
        release(job, PaymentJobStatus.REVIEW_REQUIRED, "PAYMENT_APPROVAL_REJECTED");
        markUnknown(payment, attempt, now);
      }
      case Result.Unconfirmed unconfirmed -> keepUnconfirmed(job, payment, attempt, now);
      case Result.NotFound notFound -> keepUnconfirmed(job, payment, attempt, now);
    }
    attempts.save(attempt);
  }

  /** 예약과 업무 키를 유지한 채 나중에 같은 원거래를 다시 조회한다. 시도 상한에 닿으면 운영 확인으로 넘긴다. */
  private void keepUnconfirmed(
      LeasedJob job, Payment payment, PaymentAttempt attempt, Instant now) {
    if (job.attempts() >= settings.maxAttempts()) {
      exhaust(job, payment, now);
      attempt.markUnknown(now);
      return;
    }
    release(
        job,
        PaymentJobStatus.RETRY,
        PaymentRetryPolicy.delayAfter(job.attempts()),
        "PAYMENT_APPROVAL_UNCONFIRMED");
    markUnknown(payment, attempt, now);
  }

  /** 자동 시도를 다 썼다. 금융 상태·업무 키·마일리지 예약은 그대로 두고 운영 확인으로 넘긴다. */
  private void exhaust(LeasedJob job, Payment payment, Instant now) {
    release(job, PaymentJobStatus.REVIEW_REQUIRED, "PAYMENT_APPROVAL_ATTEMPTS_EXHAUSTED");
    if (payment.getStatus() == PaymentStatus.PROCESSING) {
      payment.markUnknown(now);
      payments.save(payment);
    }
    LOG.atError()
        .addKeyValue("event", "payment.approval")
        .addKeyValue("paymentId", job.paymentId())
        .addKeyValue("jobId", job.jobId())
        .addKeyValue("attempt", job.attempts())
        .addKeyValue("result", "ATTEMPTS_EXHAUSTED")
        .log("Approval job requires review after exhausting attempts");
  }

  private void markUnknown(Payment payment, PaymentAttempt attempt, Instant now) {
    if (payment.getStatus() == PaymentStatus.PROCESSING) {
      payment.markUnknown(now);
      payments.save(payment);
    }
    attempt.markUnknown(now);
  }

  /** 승인으로 확정되면 예약해 둔 마일리지를 실제로 차감한다. */
  private MileageWallet consumeMileage(Payment payment, Instant now) {
    Optional<MileageReservation> reservation = reserved(payment);
    if (reservation.isEmpty()) {
      return null;
    }
    MileageWallet wallet = wallet(payment);
    wallet.consumeReserved(reservation.get().getAmount(), now);
    reservation.get().consume(now);
    reservations.save(reservation.get());
    entries.save(MileageEntry.debit(wallet, reservation.get(), now));
    wallets.save(wallet);
    return wallet;
  }

  /** 확정 거절되면 마일리지 예약을 풀어 준다. */
  private void releaseMileage(Payment payment, Instant now) {
    Optional<MileageReservation> reservation = reserved(payment);
    if (reservation.isEmpty()) {
      return;
    }
    MileageWallet wallet = wallet(payment);
    wallet.releaseReserved(reservation.get().getAmount(), now);
    reservation.get().release(now);
    reservations.save(reservation.get());
    entries.save(MileageEntry.release(wallet, reservation.get(), now));
    wallets.save(wallet);
  }

  private Optional<MileageReservation> reserved(Payment payment) {
    return reservations
        .findByPaymentId(payment.getId())
        .filter(r -> r.getStatus() == MileageReservationStatus.RESERVED);
  }

  private MileageWallet wallet(Payment payment) {
    return wallets
        .findByBuyerIdForUpdate(payment.getBuyerId())
        .orElseThrow(() -> new IllegalStateException("Reserved mileage wallet is missing"));
  }

  private void release(LeasedJob job, PaymentJobStatus status, String errorCode) {
    release(job, status, Duration.ZERO, errorCode);
  }

  private void release(
      LeasedJob job, PaymentJobStatus status, Duration runAfter, String errorCode) {
    if (!jobs.releaseLease(job.jobId(), job.leaseToken(), status, runAfter, errorCode)) {
      throw new ApprovalLeaseLostException();
    }
  }

  private static UUID eventId(String eventType, Payment payment) {
    return UUID.nameUUIDFromBytes(
        (eventType + ":" + payment.getId()).getBytes(StandardCharsets.UTF_8));
  }

  private static ApprovalRequest request(PaymentAttempt attempt) {
    return new ApprovalRequest(
        attempt.getProviderId(),
        attempt.getBusinessKey(),
        attempt.getMethod(),
        attempt.getAccountRef(),
        attempt.getFundingAccountRef(),
        attempt.getAmount(),
        attempt.getScenarioVersion());
  }

  record Prepared(ApprovalRequest request, boolean firstCall) {}

  /** 결과를 반영하려는 사이 임대가 끝났거나 다른 실행자가 인계했다. 반영 전체를 롤백한다. */
  static final class ApprovalLeaseLostException extends RuntimeException {
    ApprovalLeaseLostException() {
      super("approval job lease is no longer held");
    }
  }
}
