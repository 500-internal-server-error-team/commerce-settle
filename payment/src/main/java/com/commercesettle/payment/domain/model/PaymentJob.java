package com.commercesettle.payment.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Worker가 실행할 결제·환불 작업. */
@Entity
@Table(name = "payment_jobs")
public class PaymentJob extends AuditableEntity {
  @Id private UUID id;
  private UUID paymentId;
  private UUID refundId;

  @Enumerated(EnumType.STRING)
  private PaymentJobOperation operation;

  private UUID businessKey;

  @Enumerated(EnumType.STRING)
  private PaymentJobStatus status;

  private int attempts;
  private Instant nextRunAt;
  private String leaseOwner;
  private UUID leaseToken;
  private Instant leaseUntil;
  private String lastErrorCode;
  @Version private Long version;

  protected PaymentJob() {}

  private PaymentJob(Payment payment, Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.paymentId = payment.getId();
    this.operation = PaymentJobOperation.APPROVE;
    this.businessKey = payment.getCurrentCycleId();
    this.status = PaymentJobStatus.PENDING;
    this.attempts = 0;
    this.nextRunAt = getCreatedAt();
  }

  /** 외부 결제액이 있는 결제의 승인 작업을 만든다. 결제 회차마다 하나만 만든다. */
  public static PaymentJob approvalFor(Payment payment, Instant now) {
    Objects.requireNonNull(payment, "payment");
    if (payment.getAmounts().externalAmount() == 0) {
      throw new IllegalArgumentException("외부 결제액이 0원인 결제는 승인 작업을 만들지 않습니다.");
    }
    return new PaymentJob(payment, now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getRefundId() {
    return refundId;
  }

  public PaymentJobOperation getOperation() {
    return operation;
  }

  public UUID getBusinessKey() {
    return businessKey;
  }

  public PaymentJobStatus getStatus() {
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

  public String getLastErrorCode() {
    return lastErrorCode;
  }

  public Long getVersion() {
    return version;
  }
}
