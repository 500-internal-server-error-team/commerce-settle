package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.exception.OperationRejectedException;
import com.commercesettle.settlement.application.exception.OperationRejectedException.Reason;
import com.commercesettle.settlement.application.port.AuditLog;
import com.commercesettle.settlement.domain.exception.PayoutJobNotFoundException;
import com.commercesettle.settlement.domain.model.Payout;
import com.commercesettle.settlement.domain.model.PayoutJob;
import com.commercesettle.settlement.domain.model.PayoutJobStatus;
import com.commercesettle.settlement.domain.model.PayoutStatus;
import com.commercesettle.settlement.domain.model.SettlementBatch;
import com.commercesettle.settlement.domain.model.SettlementBatchStatus;
import com.commercesettle.settlement.domain.repository.PayoutJobRepository;
import com.commercesettle.settlement.domain.repository.PayoutRepository;
import com.commercesettle.settlement.domain.repository.SettlementBatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자의 지급 작업 조회와 원업무 키 재처리. 재처리는 새 금융 거래를 만들지 않고 운영 확인으로 멈춘 같은 작업을 다시 연다. 업무 키·금액·계정 사본·항목 연결은 지급에
 * 그대로 남는다.
 */
@Service
public class PayoutJobOperationService {
  static final String REPLAY = "PAYOUT_JOB_REPLAY";

  private final PayoutJobRepository jobs;
  private final PayoutRepository payouts;
  private final SettlementBatchRepository batches;
  private final IdempotentRequests idempotent;
  private final AuditLog audit;
  private final Clock clock;

  public PayoutJobOperationService(
      PayoutJobRepository jobs,
      PayoutRepository payouts,
      SettlementBatchRepository batches,
      IdempotentRequests idempotent,
      AuditLog audit,
      Clock clock) {
    this.jobs = jobs;
    this.payouts = payouts;
    this.batches = batches;
    this.idempotent = idempotent;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageView<PayoutJob> list(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid page");
    }
    long total = jobs.count();
    List<PayoutJob> found =
        (long) page * size > Integer.MAX_VALUE ? List.of() : jobs.findPage(page, size);
    return new PageView<>(found, page, size, total);
  }

  /** 같은 키·본문의 재요청은 작업의 현재 상태를 돌려주고 다시 열지 않는다. */
  @Transactional
  public PayoutJob replay(
      String operator, UUID jobId, String key, long expectedVersion, String reason) {
    return idempotent.execute(
        new IdempotentRequests.Request(
            "OPERATOR", operator, REPLAY, key, List.of(jobId, expectedVersion, reason)),
        202,
        () -> reopen(operator, jobId, expectedVersion, reason),
        PayoutJob::getId,
        id -> jobs.findById(id).orElseThrow(PayoutJobNotFoundException::new));
  }

  /**
   * 작업 행을 잠그고 버전·상태를 확인한다. 실행 중이면 임대와 충돌하므로 거절하고, 확정 거절·성공한 지급은 같은 키로 다시 돌려도 결과가 같아 거절한다. 호출 전 지급이면
   * 배치를 예약으로, 호출한 적 있는 지급이면 처리 중으로 되돌려 Worker가 원거래 조회부터 하게 한다.
   */
  private PayoutJob reopen(String operator, UUID jobId, long expectedVersion, String reason) {
    PayoutJob job = jobs.findByIdForUpdate(jobId).orElseThrow(PayoutJobNotFoundException::new);
    if (job.getVersion() != expectedVersion) {
      throw new OperationRejectedException(Reason.VERSION_CONFLICT);
    }
    if (job.getStatus() == PayoutJobStatus.RUNNING) {
      throw new OperationRejectedException(Reason.LEASE_ACTIVE);
    }
    if (job.getStatus() != PayoutJobStatus.REVIEW_REQUIRED) {
      throw new OperationRejectedException(Reason.NOT_REPLAYABLE);
    }
    Payout payout = payouts.findById(job.getPayoutId()).orElseThrow();
    boolean started = payout.getStatus() == PayoutStatus.UNKNOWN;
    if (!started && payout.getStatus() != PayoutStatus.PENDING) {
      throw new OperationRejectedException(Reason.NOT_REPLAYABLE);
    }
    SettlementBatch batch = batches.findById(payout.getBatchId()).orElseThrow();
    if (batch.getStatus() != SettlementBatchStatus.REVIEW_REQUIRED) {
      throw new OperationRejectedException(Reason.NOT_REPLAYABLE);
    }
    Instant now = clock.instant();
    PayoutJobStatus previousStatus = job.getStatus();
    int previousAttempts = job.getAttempts();
    job.reopenForReplay(now);
    batch.resumeAfterReview(started, now);
    PayoutJob saved = jobs.saveNow(job);
    jobs.recordReplay(
        jobId, expectedVersion, previousStatus, previousAttempts, operator, reason, now);
    audit.record(
        new AuditLog.Entry(
            "OPERATOR",
            operator,
            AuditLog.Action.PAYOUT_JOB_REPLAYED,
            "PAYOUT_JOB",
            jobId,
            previousStatus.name(),
            saved.getStatus().name(),
            "OPERATOR_REPLAY",
            jobId.toString(),
            now));
    return saved;
  }
}
