package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.CouponRunConflictException;
import com.commercesettle.commerce.domain.exception.CouponRunConflictException.Reason;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * 월간 우수고객 발급 회차. 혜택 월의 첫날(KST) 0시가 마감이며 전월에 구매 확정된 주문으로 대상을 고정한다. 대상 확정·검증을 마친 뒤에만 ISSUING이 되고, 확정
 * 뒤에는 대상을 다시 선정하지 않는다. 쿠폰 기한은 다음 달 1일 0시(KST)이며 지연으로 늘리지 않는다.
 */
@Entity
@Table(name = "coupon_issue_runs")
public class CouponIssueRun extends AuditableEntity {
  public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

  /** 대상·등급·할인 규칙의 버전. 재시작은 처음 저장한 값을 쓴다. */
  public static final String POLICY_VERSION = "grade-2026-11-v1";

  @Id private UUID id;
  private LocalDate targetMonth;
  private Instant cutoffAt;
  private String policyVersion;
  private UUID replacesRunId;
  private Integer targetCount;
  private Long cutoffSpend;

  @Enumerated(EnumType.STRING)
  private CouponIssueRunStatus status;

  private Instant cancelledAt;
  private String cancelledBy;
  private String cancellationReasonCode;

  @Version private Long version;

  protected CouponIssueRun() {}

  private CouponIssueRun(UUID id, LocalDate targetMonth, Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.targetMonth = firstDay(targetMonth);
    this.cutoffAt = monthStart(this.targetMonth);
    this.policyVersion = POLICY_VERSION;
    this.status = CouponIssueRunStatus.PREPARING;
  }

  /** 혜택 월의 최초 회차. 마감 전에는 만들지 않는다. */
  public static CouponIssueRun open(UUID id, LocalDate targetMonth, Instant now) {
    CouponIssueRun run = new CouponIssueRun(id, targetMonth, now);
    if (now.isBefore(run.cutoffAt)) {
      throw new IllegalArgumentException("month has not closed yet: " + targetMonth);
    }
    return run;
  }

  /** 집계 오류 교정의 대체 회차. 원회차의 월·마감·정책 버전을 그대로 쓰며 기한도 같다. */
  public static CouponIssueRun replacing(UUID id, CouponIssueRun original, Instant now) {
    if (original.status != CouponIssueRunStatus.CANCELLED) {
      throw new IllegalStateException("only a cancelled run can be replaced: " + original.id);
    }
    CouponIssueRun run = new CouponIssueRun(id, original.targetMonth, now);
    run.cutoffAt = original.cutoffAt;
    run.policyVersion = original.policyVersion;
    run.replacesRunId = original.id;
    return run;
  }

  /**
   * 발급 0건인 회차를 집계 오류로 취소한다. 만료·취소된 회차는 교정하지 않고, 기한이 지났거나 버전이 다르면 거절한다. 발급 이력·실행 여부는 호출자가 같은 회차 잠금
   * 아래에서 확인한다.
   */
  public void cancel(long expectedVersion, String operator, String reasonCode, Instant now) {
    if (status != CouponIssueRunStatus.PREPARING
        && status != CouponIssueRunStatus.ISSUING
        && status != CouponIssueRunStatus.COMPLETED) {
      throw new CouponRunConflictException(Reason.NOT_REPLACEABLE);
    }
    if (isExpiredAt(now)) {
      throw new CouponRunConflictException(Reason.EXPIRED);
    }
    if (expectedVersion != getVersion()) {
      throw new CouponRunConflictException(Reason.VERSION_CONFLICT);
    }
    status = CouponIssueRunStatus.CANCELLED;
    cancelledAt = truncate(now);
    cancelledBy = Objects.requireNonNull(operator, "operator");
    cancellationReasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    touch(now);
  }

  /** 대상 확정·검증을 마쳤다. 대상이 없으면 바로 완료한다. */
  public void confirm(int targetCount, Long cutoffSpend, Instant now) {
    requireStatus(CouponIssueRunStatus.PREPARING);
    if (targetCount < 0 || (targetCount == 0) != (cutoffSpend == null)) {
      throw new IllegalArgumentException("target count and cutoff spend do not match");
    }
    this.targetCount = targetCount;
    this.cutoffSpend = cutoffSpend;
    status = targetCount == 0 ? CouponIssueRunStatus.COMPLETED : CouponIssueRunStatus.ISSUING;
    touch(now);
  }

  public void complete(Instant now) {
    requireStatus(CouponIssueRunStatus.ISSUING);
    status = CouponIssueRunStatus.COMPLETED;
    touch(now);
  }

  /** 원래 기한이 지난 미완료 회차를 종결한다. 새 회차로 연장하지 않는다. */
  public void expire(Instant now) {
    if (status != CouponIssueRunStatus.PREPARING && status != CouponIssueRunStatus.ISSUING) {
      throw new IllegalStateException("run cannot expire from " + status);
    }
    if (now.isBefore(expiresAt())) {
      throw new IllegalStateException("run has not expired yet: " + id);
    }
    status = CouponIssueRunStatus.EXPIRED;
    touch(now);
  }

  /** 대상 기간의 시작: 혜택 월 직전 달 1일 0시(KST). */
  public Instant spendFrom() {
    return monthStart(targetMonth.minusMonths(1));
  }

  /** 발급한 쿠폰과 미발급 대상의 기한: 혜택 월 다음 달 1일 0시(KST). */
  public Instant expiresAt() {
    return monthStart(targetMonth.plusMonths(1));
  }

  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt());
  }

  public static LocalDate firstDay(LocalDate month) {
    return month.withDayOfMonth(1);
  }

  public static Instant monthStart(LocalDate month) {
    return firstDay(month).atStartOfDay(BUSINESS_ZONE).toInstant();
  }

  private void requireStatus(CouponIssueRunStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("run " + id + " is " + status + ", not " + expected);
    }
  }

  public UUID getId() {
    return id;
  }

  public LocalDate getTargetMonth() {
    return targetMonth;
  }

  public Instant getCutoffAt() {
    return cutoffAt;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public UUID getReplacesRunId() {
    return replacesRunId;
  }

  public Integer getTargetCount() {
    return targetCount;
  }

  public Long getCutoffSpend() {
    return cutoffSpend;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public String getCancelledBy() {
    return cancelledBy;
  }

  public String getCancellationReasonCode() {
    return cancellationReasonCode;
  }

  public CouponIssueRunStatus getStatus() {
    return status;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
