package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.port.AuditLog;
import com.commercesettle.commerce.application.port.CouponIssueRunExecutions;
import com.commercesettle.commerce.domain.exception.CouponIssueRunNotFoundException;
import com.commercesettle.commerce.domain.exception.CouponRunConflictException;
import com.commercesettle.commerce.domain.exception.CouponRunConflictException.Reason;
import com.commercesettle.commerce.domain.exception.CouponRunStateUnverifiedException;
import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import com.commercesettle.commerce.domain.repository.CouponIssueRunRepository;
import com.commercesettle.commerce.domain.repository.CouponIssueTargetRepository;
import com.commercesettle.commerce.domain.repository.CouponIssueTargetRepository.Issuance;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 월간 회차의 운영 조회와 집계 오류 교정. 교정은 원회차의 실행자 잠금을 쥔 채 진행 중 실행이 없는지 확인한 뒤, 회차 행 잠금 아래에서 발급 0건·기한·버전을 다시
 * 검사하고 원회차 취소·대체 회차·감사·멱등 결과를 한 트랜잭션에 저장한다. 발급 Step도 같은 회차 행을 잠그므로 첫 발급과 교정 중 먼저 커밋한 쪽만 반영된다.
 */
@Service
public class CouponIssueRunAdminService {
  public static final String AGGREGATION_ERROR = "AGGREGATION_ERROR";
  private static final String AGGREGATE = "COUPON_ISSUE_RUN";

  private final CouponIssueRunRepository runs;
  private final CouponIssueTargetRepository targets;
  private final CouponIssueRunExecutions executions;
  private final IdempotentRequests idempotency;
  private final AuditLog audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public CouponIssueRunAdminService(
      CouponIssueRunRepository runs,
      CouponIssueTargetRepository targets,
      CouponIssueRunExecutions executions,
      IdempotentRequests idempotency,
      AuditLog audit,
      TransactionTemplate transactions,
      Clock clock) {
    this.runs = runs;
    this.targets = targets;
    this.executions = executions;
    this.idempotency = idempotency;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** 회차와 같은 조회 스냅샷의 발급 건수·대체 회차. */
  public record RunView(CouponIssueRun run, long issuedCount, UUID replacementRunId) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<RunView> runs(
      LocalDate targetMonth, CouponIssueRunStatus status, int page, int size) {
    PageResult<CouponIssueRun> result = runs.find(targetMonth, status, page, size);
    return new PageResult<>(
        result.items().stream().map(this::view).toList(),
        result.page(),
        result.size(),
        result.totalElements());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RunView run(UUID runId) {
    return view(runs.findById(runId).orElseThrow(() -> new CouponIssueRunNotFoundException(runId)));
  }

  /**
   * 발급 전 집계 오류 회차를 취소하고 대체 회차를 만든다. 같은 키·본문의 재요청은 저장한 결과를 버전·현재 상태 검사보다 먼저 돌려준다. 새 쿠폰 발급은 이후 스케줄러가
   * 대체 회차로 시작한다.
   */
  @Transactional(propagation = Propagation.NEVER)
  public RunView replace(
      String operator, UUID runId, String idempotencyKey, long expectedVersion, String reasonCode) {
    IdempotentRequests.Request request =
        new IdempotentRequests.Request(
            "OPERATOR",
            operator,
            "COUPON_ISSUE_RUN_REPLACE",
            idempotencyKey,
            List.of(runId, expectedVersion, reasonCode));
    Optional<RunView> prior =
        transactions.execute(status -> idempotency.replayPermanent(request, this::viewOf));
    if (prior != null && prior.isPresent()) {
      return prior.get();
    }
    runs.findById(runId).orElseThrow(() -> new CouponIssueRunNotFoundException(runId));
    Optional<CouponIssueRunExecutions.Held> held = executions.tryLock(runId);
    if (held.isEmpty()) {
      throw new CouponRunConflictException(Reason.EXECUTION_ACTIVE);
    }
    try (CouponIssueRunExecutions.Held ignored = held.get()) {
      boolean active;
      try {
        active = executions.hasActiveExecution(runId);
      } catch (RuntimeException e) {
        throw new CouponRunStateUnverifiedException("job executions of run cannot be read");
      }
      if (active) {
        throw new CouponRunConflictException(Reason.EXECUTION_ACTIVE);
      }
      UUID replacementId = UUID.randomUUID();
      return transactions.execute(
          status ->
              idempotency.executePermanent(
                  request,
                  replacementId,
                  201,
                  recordId ->
                      cancelAndReplace(operator, runId, replacementId, expectedVersion, reasonCode),
                  this::viewOf));
    }
  }

  private RunView cancelAndReplace(
      String operator, UUID runId, UUID replacementId, long expectedVersion, String reasonCode) {
    if (!AGGREGATION_ERROR.equals(reasonCode)) {
      throw new IllegalArgumentException("unsupported reason: " + reasonCode);
    }
    CouponIssueRun original =
        runs.findByIdForUpdate(runId).orElseThrow(() -> new CouponIssueRunNotFoundException(runId));
    Instant now = clock.instant();
    if (!CouponIssueRun.POLICY_VERSION.equals(original.getPolicyVersion())) {
      throw new CouponRunStateUnverifiedException("stored policy version is not supported");
    }
    Issuance issuance;
    try {
      issuance = targets.issuance(runId);
    } catch (DataAccessException e) {
      throw new CouponRunStateUnverifiedException("issue history of run cannot be read");
    }
    if (issuance.any()) {
      throw new CouponRunConflictException(Reason.NOT_REPLACEABLE);
    }
    CouponIssueRunStatus before = original.getStatus();
    original.cancel(expectedVersion, operator, reasonCode, now);
    runs.save(original);
    CouponIssueRun replacement =
        runs.saveNew(CouponIssueRun.replacing(replacementId, original, now));
    record(operator, AuditLog.Action.COUPON_ISSUE_RUN_REPLACED, original, before, runId, now);
    record(operator, AuditLog.Action.COUPON_ISSUE_RUN_CREATED, replacement, null, runId, now);
    return new RunView(replacement, 0, null);
  }

  private void record(
      String operator,
      AuditLog.Action action,
      CouponIssueRun run,
      CouponIssueRunStatus before,
      UUID originalRunId,
      Instant now) {
    audit.record(
        new AuditLog.Entry(
            "OPERATOR",
            operator,
            action,
            AGGREGATE,
            run.getId(),
            before == null ? null : before.name(),
            run.getStatus().name(),
            AGGREGATION_ERROR,
            null,
            originalRunId.toString(),
            now));
  }

  private RunView viewOf(UUID runId) {
    return view(runs.findById(runId).orElseThrow(() -> new CouponIssueRunNotFoundException(runId)));
  }

  private RunView view(CouponIssueRun run) {
    return new RunView(
        run,
        targets.issuance(run.getId()).issuedTargets(),
        runs.findReplacementId(run.getId()).orElse(null));
  }
}
