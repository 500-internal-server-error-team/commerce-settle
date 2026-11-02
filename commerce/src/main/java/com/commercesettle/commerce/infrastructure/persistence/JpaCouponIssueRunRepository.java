package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import com.commercesettle.commerce.domain.repository.CouponIssueRunRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaCouponIssueRunRepository implements CouponIssueRunRepository {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final SpringDataCouponIssueRunRepository runs;
  private final JdbcOperations jdbc;

  JpaCouponIssueRunRepository(SpringDataCouponIssueRunRepository runs, JdbcOperations jdbc) {
    this.runs = runs;
    this.jdbc = jdbc;
  }

  @Override
  public CouponIssueRun save(CouponIssueRun run) {
    return runs.saveAndFlush(run);
  }

  /** 취소되지 않은 월별 회차의 부분 유일 인덱스로 충돌을 무시해 동시 생성에도 트랜잭션을 실패시키지 않는다. */
  @Override
  public void saveIfAbsent(CouponIssueRun run) {
    TransactionLimits.apply(jdbc);
    jdbc.update(
        """
        INSERT INTO commerce.coupon_issue_runs
            (id, target_month, cutoff_at, policy_version, status, created_at, updated_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0)
        ON CONFLICT (target_month) WHERE status <> 'CANCELLED' DO NOTHING
        """,
        run.getId(),
        Date.valueOf(run.getTargetMonth()),
        Timestamp.from(run.getCutoffAt()),
        run.getPolicyVersion(),
        run.getStatus().name(),
        Timestamp.from(run.getCreatedAt()),
        Timestamp.from(run.getUpdatedAt()));
  }

  /** 교정은 원회차 CANCELLED를 먼저 반영한 뒤 같은 월의 대체 회차를 저장한다. 취소되지 않은 월별 회차 유일성은 DB가 지킨다. */
  @Override
  public CouponIssueRun saveNew(CouponIssueRun run) {
    return runs.saveAndFlush(run);
  }

  @Override
  public Optional<CouponIssueRun> findById(UUID runId) {
    return runs.findById(runId);
  }

  @Override
  public PageResult<CouponIssueRun> find(
      LocalDate targetMonth, CouponIssueRunStatus status, int page, int size) {
    Page<CouponIssueRun> result =
        runs.search(targetMonth, status, PageRequest.of(page, size, NEWEST_FIRST));
    return new PageResult<>(
        result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
  }

  @Override
  public Optional<UUID> findReplacementId(UUID runId) {
    return runs.findIdByReplacesRunId(runId);
  }

  @Override
  public Optional<CouponIssueRun> findCurrent(LocalDate targetMonth) {
    return runs.findByTargetMonthAndStatusNot(targetMonth, CouponIssueRunStatus.CANCELLED);
  }

  @Override
  public List<LocalDate> findUnfinishedMonthsBefore(LocalDate targetMonth) {
    return runs.findMonthsBefore(
        targetMonth, List.of(CouponIssueRunStatus.PREPARING, CouponIssueRunStatus.ISSUING));
  }

  @Override
  public Optional<CouponIssueRun> findByIdForUpdate(UUID runId) {
    TransactionLimits.apply(jdbc);
    return runs.findLockedById(runId);
  }
}
