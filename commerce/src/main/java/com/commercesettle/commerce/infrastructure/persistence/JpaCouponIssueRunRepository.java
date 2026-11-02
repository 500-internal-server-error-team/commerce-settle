package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.CouponIssueRun;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import com.commercesettle.commerce.domain.repository.CouponIssueRunRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaCouponIssueRunRepository implements CouponIssueRunRepository {
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

  @Override
  public Optional<CouponIssueRun> findCurrent(LocalDate targetMonth) {
    return runs.findByTargetMonthAndStatusNot(targetMonth, CouponIssueRunStatus.CANCELLED);
  }

  @Override
  public Optional<CouponIssueRun> findByIdForUpdate(UUID runId) {
    TransactionLimits.apply(jdbc);
    return runs.findLockedById(runId);
  }
}
