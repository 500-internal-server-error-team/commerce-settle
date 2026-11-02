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

/**
 * 외부 승인 시도 하나. 외부 호출 전에 provider·결제수단·계정·재원·금액·시나리오를 고정해, 호출 전 중단 뒤 재전송이나 원거래 조회도 같은 값으로 한다. 결제 회차
 * ID를 대역 업무 키로 쓴다.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt extends AuditableEntity {
  @Id private UUID id;
  private UUID paymentId;
  private int attemptNo;
  private UUID cycleId;
  private String providerId;
  private String method;
  private String accountRef;
  private String fundingAccountRef;
  private UUID businessKey;
  private UUID externalRef;
  private long amount;

  @Enumerated(EnumType.STRING)
  private PaymentAttemptStatus status;

  private String scenarioVersion;
  @Version private Long version;

  protected PaymentAttempt() {}

  private PaymentAttempt(
      Payment payment,
      String providerId,
      String method,
      String accountRef,
      String fundingAccountRef,
      String scenarioVersion,
      Instant now) {
    super(now);
    this.id = UUID.randomUUID();
    this.paymentId = payment.getId();
    this.attemptNo = 1;
    this.cycleId = payment.getCurrentCycleId();
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    this.method = Objects.requireNonNull(method, "method");
    this.accountRef = Objects.requireNonNull(accountRef, "accountRef");
    this.fundingAccountRef = Objects.requireNonNull(fundingAccountRef, "fundingAccountRef");
    this.businessKey = payment.getCurrentCycleId();
    this.amount = payment.getAmounts().externalAmount();
    this.status = PaymentAttemptStatus.PROCESSING;
    this.scenarioVersion = Objects.requireNonNull(scenarioVersion, "scenarioVersion");
  }

  /** 외부 호출 직전에 만든다. 외부 결제액이 없는 결제는 시도를 만들지 않는다. */
  public static PaymentAttempt start(
      Payment payment,
      String providerId,
      String method,
      String accountRef,
      String fundingAccountRef,
      String scenarioVersion,
      Instant now) {
    if (payment.getAmounts().externalAmount() <= 0) {
      throw new IllegalArgumentException("외부 결제액이 0원인 결제는 승인 시도를 만들지 않습니다.");
    }
    return new PaymentAttempt(
        payment, providerId, method, accountRef, fundingAccountRef, scenarioVersion, now);
  }

  public void approve(UUID externalRef, Instant now) {
    this.externalRef = externalRef;
    this.status = PaymentAttemptStatus.APPROVED;
    touch(now);
  }

  public void decline(UUID externalRef, Instant now) {
    this.externalRef = externalRef;
    this.status = PaymentAttemptStatus.DECLINED;
    touch(now);
  }

  public void markUnknown(Instant now) {
    if (status != PaymentAttemptStatus.UNKNOWN) {
      this.status = PaymentAttemptStatus.UNKNOWN;
      touch(now);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public int getAttemptNo() {
    return attemptNo;
  }

  public UUID getCycleId() {
    return cycleId;
  }

  public String getProviderId() {
    return providerId;
  }

  public String getMethod() {
    return method;
  }

  public String getAccountRef() {
    return accountRef;
  }

  public String getFundingAccountRef() {
    return fundingAccountRef;
  }

  public UUID getBusinessKey() {
    return businessKey;
  }

  public UUID getExternalRef() {
    return externalRef;
  }

  public long getAmount() {
    return amount;
  }

  public PaymentAttemptStatus getStatus() {
    return status;
  }

  public String getScenarioVersion() {
    return scenarioVersion;
  }

  public Long getVersion() {
    return version;
  }
}
