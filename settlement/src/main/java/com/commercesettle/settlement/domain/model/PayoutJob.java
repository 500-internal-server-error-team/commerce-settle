package com.commercesettle.settlement.domain.model;

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
