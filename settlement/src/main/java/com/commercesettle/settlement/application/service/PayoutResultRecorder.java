package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.PayoutWorkerSettings;
import com.commercesettle.settlement.application.exception.LeaseLostException;
import com.commercesettle.settlement.application.port.PayoutGateway.PayoutRequest;
import com.commercesettle.settlement.application.port.PayoutGateway.Result;
import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.model.PayoutJobStatus;
import com.commercesettle.settlement.domain.model.PayoutStatus;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository.LeasedJob;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지급 Worker의 로컬 트랜잭션 단계. 대역 호출 전후로 나눠 외부 호출 중에는 DB 트랜잭션을 열어 두지 않는다. 결과 반영은 유효한 임대에서만 하며, 임대를 잃었으면
 * 지급·항목·배치·작업 변경을 모두 롤백한다.
 */
@Component
class PayoutResultRecorder {
  private final PayoutJobRepository jobs;
  private final PayoutRepository payouts;
  private final SettlementBatchRepository batches;
  private final SettlementItemRepository items;
  private final PayoutWorkerSettings settings;
  private final Clock clock;

  PayoutResultRecorder(
      PayoutJobRepository jobs,
      PayoutRepository payouts,
      SettlementBatchRepository batches,
      SettlementItemRepository items,
      PayoutWorkerSettings settings,
      Clock clock) {
    this.jobs = jobs;
    this.payouts = payouts;
    this.batches = batches;
    this.items = items;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * 호출할 요청을 준비한다. 처음 실행하는 지급이면 지급·배치를 처리 중으로 바꿔 두고 실행(POST)을, 이미 처리 중이었으면 결과를 모르므로 원거래 조회부터 하게 한다.
   */
  @Transactional
  public Optional<Prepared> prepare(LeasedJob job) {
    if (!jobs.holdsLease(job.jobId(), job.leaseToken())) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    Payout payout = payouts.findById(job.payoutId()).orElseThrow();
    boolean firstCall = payout.getStatus() == PayoutStatus.PENDING;
    if (firstCall) {
      payout.startProcessing(now);
      batch(payout).startProcessing(now);
    }
    return Optional.of(new Prepared(request(payout), firstCall));
  }

  @Transactional
  public void record(LeasedJob job, Result result) {
    Instant now = clock.instant();
    Payout payout = payouts.findById(job.payoutId()).orElseThrow();
    switch (result) {
      case Result.Succeeded succeeded -> {
        release(job, PayoutJobStatus.DONE, Duration.ZERO);
        payout.succeed(succeeded.succeededAt(), succeeded.externalRef(), now);
        items.completePayout(payout.getId(), now);
        batch(payout).complete(now);
      }
      case Result.Declined declined -> {
        release(job, PayoutJobStatus.REVIEW_REQUIRED, Duration.ZERO);
        payout.decline(declined.externalRef(), now);
        batch(payout).requireReview(now);
      }
      case Result.Rejected rejected -> {
        release(job, PayoutJobStatus.REVIEW_REQUIRED, Duration.ZERO);
        payout.markUnknown(now);
        batch(payout).requireReview(now);
      }
      case Result.Unconfirmed unconfirmed -> keepUnconfirmed(job, payout, now);
      case Result.NotFound notFound -> keepUnconfirmed(job, payout, now);
    }
  }

  /** 예약과 업무 키를 유지한 채 나중에 같은 원거래를 다시 조회한다. 시도 상한을 넘으면 운영 확인으로 넘긴다. */
  private void keepUnconfirmed(LeasedJob job, Payout payout, Instant now) {
    payout.markUnknown(now);
    if (job.attempts() >= settings.maxAttempts()) {
      release(job, PayoutJobStatus.REVIEW_REQUIRED, Duration.ZERO);
      batch(payout).requireReview(now);
      return;
    }
    release(job, PayoutJobStatus.RETRY, settings.unconfirmedRetryDelay());
  }

  private void release(LeasedJob job, PayoutJobStatus status, Duration runAfter) {
    if (!jobs.releaseLease(job.jobId(), job.leaseToken(), status, runAfter)) {
      throw new LeaseLostException("payout job lease is no longer held");
    }
  }

  private SettlementBatch batch(Payout payout) {
    return batches.findById(payout.getBatchId()).orElseThrow();
  }

  private static PayoutRequest request(Payout payout) {
    return new PayoutRequest(
        payout.getBusinessKey(),
        payout.getFundingAccountRef(),
        payout.getRecipientAccountRef(),
        payout.getAmount(),
        payout.getScenarioVersion());
  }

  record Prepared(PayoutRequest request, boolean firstCall) {}
}
