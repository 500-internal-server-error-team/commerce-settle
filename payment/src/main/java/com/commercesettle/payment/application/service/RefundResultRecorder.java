package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.dto.RefundWorkerSettings;
import com.commercesettle.payment.application.event.RefundFinancialCompletedPayload;
import com.commercesettle.payment.application.event.RefundReleaseRequestedPayload;
import com.commercesettle.payment.application.port.PaymentGateway.Result;
import com.commercesettle.payment.application.port.RefundGateway.RefundRequest;
import com.commercesettle.payment.domain.model.ExternalRefundStatus;
import com.commercesettle.payment.domain.model.MileageEntry;
import com.commercesettle.payment.domain.model.MileageWallet;
import com.commercesettle.payment.domain.model.Payment;
import com.commercesettle.payment.domain.model.PaymentAttempt;
import com.commercesettle.payment.domain.model.PaymentAttemptStatus;
import com.commercesettle.payment.domain.model.PaymentJobStatus;
import com.commercesettle.payment.domain.model.Refund;
import com.commercesettle.payment.domain.model.RefundSaga;
import com.commercesettle.payment.domain.model.RefundStatus;
import com.commercesettle.payment.domain.repository.MileageEntryRepository;
import com.commercesettle.payment.domain.repository.MileageWalletRepository;
import com.commercesettle.payment.domain.repository.PaymentAttemptRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository;
import com.commercesettle.payment.domain.repository.PaymentJobRepository.LeasedJob;
import com.commercesettle.payment.domain.repository.PaymentRepository;
import com.commercesettle.payment.domain.repository.RefundRepository;
import com.commercesettle.payment.domain.repository.RefundSagaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 Worker의 로컬 트랜잭션 단계. 외부 환불 호출 전후로 나눠 호출 중에는 DB 트랜잭션을 열어 두지 않는다. 결과 반영은 유효한 임대에서만 하며, 임대를 잃었으면
 * 환불·SAGA·마일리지·Outbox 변경을 모두 롤백한다.
 *
 * <p>외부 환불 성공을 확인한 트랜잭션에서 사용 마일리지 복원·복원 원장·금융 환불 상태·RefundFinancialCompleted를 함께 저장한다. 이 트랜잭션이 커밋되지
 * 않으면 다음 실행자가 원거래 조회로 성공을 다시 확인하며 외부 환불을 새 키로 반복하지 않는다. 확정 거절은 마일리지를 복원하지 않고 정산금 예약 해제를 요청한다.
 */
@Component
class RefundResultRecorder {
  static final String EXTERNAL_DECLINED = "REFUND_EXTERNAL_DECLINED";
  static final String ORIGINAL_APPROVAL_MISSING = "REFUND_ORIGINAL_APPROVAL_MISSING";
  static final String EXTERNAL_UNCONFIRMED = "REFUND_EXTERNAL_UNCONFIRMED";
  static final String EXTERNAL_REJECTED = "REFUND_EXTERNAL_REJECTED";
  static final String ATTEMPTS_EXHAUSTED = "REFUND_ATTEMPTS_EXHAUSTED";

  private static final Logger LOG = LoggerFactory.getLogger(RefundResultRecorder.class);

  private final PaymentJobRepository jobs;
  private final RefundRepository refunds;
  private final RefundSagaRepository sagas;
  private final PaymentRepository payments;
  private final PaymentAttemptRepository attempts;
  private final MileageWalletRepository wallets;
  private final MileageEntryRepository entries;
  private final RefundEvents events;
  private final PaymentNotices notices;
  private final RefundWorkerSettings settings;
  private final Clock clock;

  RefundResultRecorder(
      PaymentJobRepository jobs,
      RefundRepository refunds,
      RefundSagaRepository sagas,
      PaymentRepository payments,
      PaymentAttemptRepository attempts,
      MileageWalletRepository wallets,
      MileageEntryRepository entries,
      RefundEvents events,
      PaymentNotices notices,
      RefundWorkerSettings settings,
      Clock clock) {
    this.jobs = jobs;
    this.refunds = refunds;
    this.sagas = sagas;
    this.payments = payments;
    this.attempts = attempts;
    this.wallets = wallets;
    this.entries = entries;
    this.events = events;
    this.notices = notices;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * 호출할 요청을 준비한다. 처음 외부 환불하는 환불은 호출 기록(REQUESTED)을 남겨 실행(POST)을, 이미 호출했을 수 있으면 원거래 조회부터 하게 한다. 외부
   * 결제액이 0원이면 호출 없이 복원으로 넘긴다. 이미 결과가 반영된 환불은 작업만 끝낸다. 환불 행 잠금은 대기 상한 안에서만 기다리고, 잠근 뒤 임대에 호출 제한 시간만큼
   * 남지 않았으면 호출하지 않는다.
   */
  @Transactional
  public Optional<Prepared> prepare(LeasedJob job) {
    Refund refund = lock(job);
    // 잠금을 기다리는 사이 임대가 만료됐을 수 있어 잠근 뒤 확인한다.
    if (!jobs.holdsLease(job.jobId(), job.leaseToken(), settings.httpTimeout())) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (job.attempts() > settings.maxAttempts()) {
      exhaust(job, refund, now);
      refunds.save(refund);
      return Optional.empty();
    }
    if (!refund.awaitsExternalResult()) {
      // 결과를 이미 반영했다. 호출하지 않고 작업만 끝낸다.
      release(job, PaymentJobStatus.DONE, null);
      return Optional.empty();
    }
    if (refund.getExternalStatus() == ExternalRefundStatus.NOT_REQUIRED) {
      return Optional.of(new Prepared(Optional.empty(), false));
    }
    Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
    Optional<PaymentAttempt> original =
        attempts
            .findByPaymentIdAndCycleId(payment.getId(), payment.getCurrentCycleId())
            .filter(attempt -> attempt.getStatus() == PaymentAttemptStatus.APPROVED)
            .filter(attempt -> attempt.getExternalRef() != null);
    if (original.isEmpty()) {
      release(job, PaymentJobStatus.REVIEW_REQUIRED, ORIGINAL_APPROVAL_MISSING);
      return Optional.empty();
    }
    RefundRequest request =
        new RefundRequest(
            original.get().getProviderId(),
            original.get().getExternalRef(),
            refund.getBusinessKey(),
            refund.getExternalAmount(),
            original.get().getScenarioVersion());
    boolean firstCall = refund.getExternalStatus() == ExternalRefundStatus.PENDING;
    if (firstCall) {
      refund.externalRequested(now);
      refunds.save(refund);
    }
    return Optional.of(new Prepared(Optional.of(request), firstCall));
  }

  /** 외부 결과(호출하지 않았으면 빈 값)를 반영한다. */
  @Transactional
  public void record(LeasedJob job, Optional<Result> result) {
    Instant now = clock.instant();
    Refund refund = lock(job);
    RefundSaga saga = sagas.findByRefundId(refund.getId()).orElseThrow();
    Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
    if (!refund.awaitsExternalResult()) {
      release(job, PaymentJobStatus.DONE, null);
      return;
    }
    if (result.isEmpty()) {
      release(job, PaymentJobStatus.DONE, null);
      refund.externalSkipped(now);
      saga.externalSettled(now);
      restore(refund, saga, payment, now);
    } else {
      switch (result.get()) {
        case Result.Succeeded succeeded -> {
          release(job, PaymentJobStatus.DONE, null);
          refund.externalSucceeded(succeeded.externalRef(), now);
          saga.externalSettled(now);
          restore(refund, saga, payment, now);
        }
        case Result.Declined declined -> {
          release(job, PaymentJobStatus.DONE, null);
          refund.externalDeclined(now);
          saga.externalDeclined(EXTERNAL_DECLINED, now);
          events.append(
              "RefundReleaseRequested",
              payment,
              refund,
              new RefundReleaseRequestedPayload(
                  refund.getId(), payment.getId(), saga.getReservationRef()),
              now);
        }
        case Result.Rejected rejected -> {
          release(job, PaymentJobStatus.REVIEW_REQUIRED, EXTERNAL_REJECTED);
          refund.externalUnknown(now);
          saga.recordError(EXTERNAL_REJECTED, now);
        }
        case Result.Unconfirmed unconfirmed -> keepUnconfirmed(job, refund, saga, now);
        case Result.NotFound notFound -> keepUnconfirmed(job, refund, saga, now);
      }
    }
    refunds.save(refund);
    sagas.save(saga);
    var log = refund.getStatus() == RefundStatus.UNKNOWN ? LOG.atWarn() : LOG.atInfo();
    log.addKeyValue("event", "payment.refund")
        .addKeyValue("refundId", refund.getId())
        .addKeyValue("jobId", job.jobId())
        .addKeyValue("attempt", job.attempts())
        .addKeyValue("result", refund.getStatus().name())
        .log("Refund job result recorded");
  }

  /** 사용 마일리지를 원래 지갑에 복원하고 금융 환불 완료를 Settlement에 알린다. 마일리지를 쓰지 않았으면 복원 원장 없이 기록만 한다. */
  private void restore(Refund refund, RefundSaga saga, Payment payment, Instant now) {
    MileageWallet wallet = null;
    if (refund.getMileageAmount() > 0) {
      wallet =
          wallets
              .findByBuyerIdForUpdate(payment.getBuyerId())
              .orElseThrow(() -> new IllegalStateException("Refund mileage wallet is missing"));
      wallet.restore(refund.getMileageAmount(), now);
      entries.save(MileageEntry.restore(wallet, refund, now));
      wallets.save(wallet);
    }
    refund.mileageRestored(now);
    saga.mileageRestored(now);
    events.append(
        "RefundFinancialCompleted",
        payment,
        refund,
        new RefundFinancialCompletedPayload(
            refund.getId(),
            payment.getId(),
            refund.getExternalAmount(),
            refund.getMileageAmount(),
            refund.getMileageRestoredAt().atOffset(ZoneOffset.UTC)),
        now);
    notices.completed(
        payment, refund.getId(), wallet, now, Objects.requireNonNullElse(payment.getVersion(), 0L));
  }

  /** 예약과 업무 키를 유지한 채 같은 원거래를 다시 조회한다. 시도 상한에 닿으면 운영 확인으로 넘긴다. */
  private void keepUnconfirmed(LeasedJob job, Refund refund, RefundSaga saga, Instant now) {
    if (job.attempts() >= settings.maxAttempts()) {
      exhaust(job, refund, now);
      return;
    }
    release(
        job,
        PaymentJobStatus.RETRY,
        PaymentRetryPolicy.delayAfter(job.attempts()),
        EXTERNAL_UNCONFIRMED);
    refund.externalUnknown(now);
    saga.recordError(EXTERNAL_UNCONFIRMED, now);
  }

  /** 자동 시도를 다 썼다. 정산금 예약·업무 키는 그대로 두고 결과 불명으로 운영 확인에 넘긴다. */
  private void exhaust(LeasedJob job, Refund refund, Instant now) {
    release(job, PaymentJobStatus.REVIEW_REQUIRED, ATTEMPTS_EXHAUSTED);
    if (refund.getStatus() == RefundStatus.EXTERNAL_PENDING
        && refund.getExternalStatus() == ExternalRefundStatus.REQUESTED) {
      refund.externalUnknown(now);
    }
    LOG.atError()
        .addKeyValue("event", "payment.refund")
        .addKeyValue("refundId", refund.getId())
        .addKeyValue("jobId", job.jobId())
        .addKeyValue("attempt", job.attempts())
        .addKeyValue("result", "ATTEMPTS_EXHAUSTED")
        .log("Refund job requires review after exhausting attempts");
  }

  private Refund lock(LeasedJob job) {
    // 잠그지 않고 먼저 읽으면 영속성 컨텍스트의 이전 상태를 잠근 뒤에도 쓰게 되므로 처음부터 잠가 읽는다.
    // 저장소가 잠금 전에 대기 상한을 걸어, 경합이 길면 임대 만료 전에 예외로 롤백한다.
    return refunds.findByBusinessKeyForUpdate(job.businessKey()).orElseThrow();
  }

  private void release(LeasedJob job, PaymentJobStatus status, String errorCode) {
    release(job, status, Duration.ZERO, errorCode);
  }

  private void release(
      LeasedJob job, PaymentJobStatus status, Duration runAfter, String errorCode) {
    if (!jobs.releaseLease(job.jobId(), job.leaseToken(), status, runAfter, errorCode)) {
      throw new RefundLeaseLostException();
    }
  }

  /** 호출할 요청. 외부 결제액이 0원이면 요청이 없고 호출 없이 복원한다. */
  record Prepared(Optional<RefundRequest> request, boolean firstCall) {}

  /** 결과를 반영하려는 사이 임대가 끝났거나 다른 실행자가 인계했다. 반영 전체를 롤백한다. */
  static final class RefundLeaseLostException extends RuntimeException {
    RefundLeaseLostException() {
      super("refund job lease is no longer held");
    }
  }
}
