package com.commercesettle.settlement.domain.model;

import com.commercesettle.settlement.domain.exception.InvalidPayoutTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 지급 하나를 실행하는 영속 작업. 지급과 같은 업무 키를 쓰고, 지급 Worker가 도래한 작업을 임대해 실행한다. */
@Entity
@Table(name = "payout_jobs")
public class PayoutJob extends AuditableEntity {
  @Id private UUID id;

  @Column(length = 55)
  private String traceParent;

  private UUID payoutId;
  private UUID businessKey;

  @Enumerated(EnumType.STRING)
  private PayoutJobStatus status;

  private int attempts;
  private Instant nextRunAt;
  private String leaseOwner;
  private UUID leaseToken;
  private Instant leaseUntil;
  @Version private Long version;

  protected PayoutJob() {}

  private PayoutJob(UUID id, UUID payoutId, UUID businessKey, Instant now) {
    super(now);
    this.id = id;
    this.payoutId = Objects.requireNonNull(payoutId, "payoutId");
    this.businessKey = Objects.requireNonNull(businessKey, "businessKey");
    this.status = PayoutJobStatus.PENDING;
    this.nextRunAt = truncate(now);
  }

  /** 바로 실행할 수 있는 지급 작업을 만든다. */
  public static PayoutJob schedule(Payout payout, Instant now) {
    return new PayoutJob(UUID.randomUUID(), payout.getId(), payout.getBusinessKey(), now);
  }

  /**
   * 운영 확인으로 멈춘 작업을 같은 업무 키로 다시 연다. 시도 수를 0으로 되돌려 새 라운드를 열고 바로 실행할 수 있게 한다. 지급이 이미 호출됐으면 Worker가 원거래
   * 조회부터 한다.
   */
  public void reopenForReplay(Instant now) {
    if (status != PayoutJobStatus.REVIEW_REQUIRED) {
      throw new InvalidPayoutTransitionException("payout job", status, PayoutJobStatus.PENDING);
    }
    this.status = PayoutJobStatus.PENDING;
    this.attempts = 0;
    this.nextRunAt = truncate(now);
    this.leaseOwner = null;
    this.leaseToken = null;
    this.leaseUntil = null;
    touch(now);
  }

  /** 최초 저장 때만 인프라 어댑터가 설정하는 관측 메타데이터다. */
  public void captureTraceParent(String parent) {
    if (traceParent == null) traceParent = parent;
  }

  public String getTraceParent() {
    return traceParent;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPayoutId() {
    return payoutId;
  }

  public UUID getBusinessKey() {
    return businessKey;
  }

  public PayoutJobStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getNextRunAt() {
    return nextRunAt;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public UUID getLeaseToken() {
    return leaseToken;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public Long getVersion() {
    return version;
  }
}
