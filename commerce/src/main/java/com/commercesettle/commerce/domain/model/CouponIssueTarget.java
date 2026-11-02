package com.commercesettle.commerce.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 회차에 고정한 월간 우수고객 한 명. ID가 발급 업무 키이며 같은 대상의 재시도는 원쿠폰을 재사용한다. */
@Entity
@Table(name = "coupon_issue_targets")
public class CouponIssueTarget extends AuditableEntity {
  @Id private UUID id;
  private UUID runId;
  private UUID templateId;
  private UUID buyerId;
  private long externalSpend;
  private int rankNo;
  private UUID buyerCouponId;

  @Enumerated(EnumType.STRING)
  private CouponIssueTargetStatus status;

  @Version private Long version;

  protected CouponIssueTarget() {}

  public void issue(UUID couponId, Instant now) {
    requirePending();
    buyerCouponId = Objects.requireNonNull(couponId, "couponId");
    status = CouponIssueTargetStatus.ISSUED;
    touch(now);
  }

  public void expire(Instant now) {
    requirePending();
    status = CouponIssueTargetStatus.EXPIRED;
    touch(now);
  }

  private void requirePending() {
    if (status != CouponIssueTargetStatus.PENDING) {
      throw new IllegalStateException("target " + id + " is " + status);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getRunId() {
    return runId;
  }

  public UUID getTemplateId() {
    return templateId;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public long getExternalSpend() {
    return externalSpend;
  }

  public int getRankNo() {
    return rankNo;
  }

  public UUID getBuyerCouponId() {
    return buyerCouponId;
  }

  public CouponIssueTargetStatus getStatus() {
    return status;
  }
}
