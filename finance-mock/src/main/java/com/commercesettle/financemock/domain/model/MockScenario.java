package com.commercesettle.financemock.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 대역·버전별 장애 시험 설정. 만든 뒤 바꾸지 않는다. 신규 거래의 결과는 seed·대역·작업·업무 키에서 정해 스레드 순서나 재요청에 따라 바뀌지 않는다. */
@Entity
@Table(name = "mock_scenarios")
public class MockScenario {
  public static final String PAYOUT_PROVIDER = "PAYOUT";

  @Id private UUID id;
  private String providerId;
  private String scenarioVersion;
  private int minDelayMs;
  private int maxDelayMs;
  private BigDecimal rejectionRate;
  private BigDecimal responseLossRate;
  private BigDecimal lookupFailureRate;
  private int lookupMinDelayMs;
  private int lookupMaxDelayMs;
  private long seed;
  private Instant createdAt;

  protected MockScenario() {}

  private MockScenario(
      String providerId,
      String scenarioVersion,
      Delay delay,
      BigDecimal rejectionRate,
      BigDecimal responseLossRate,
      BigDecimal lookupFailureRate,
      Delay lookupDelay,
      long seed,
      Instant now) {
    this.id = UUID.randomUUID();
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    this.scenarioVersion = Objects.requireNonNull(scenarioVersion, "scenarioVersion");
    this.minDelayMs = delay.minMs();
    this.maxDelayMs = delay.maxMs();
    this.rejectionRate = requireRate(rejectionRate);
    this.responseLossRate = requireRate(responseLossRate);
    this.lookupFailureRate = requireRate(lookupFailureRate);
    this.lookupMinDelayMs = lookupDelay.minMs();
    this.lookupMaxDelayMs = lookupDelay.maxMs();
    this.seed = seed;
    this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
  }

  public static MockScenario payout(
      String scenarioVersion,
      Delay delay,
      BigDecimal rejectionRate,
      BigDecimal responseLossRate,
      BigDecimal lookupFailureRate,
      Delay lookupDelay,
      long seed,
      Instant now) {
    return new MockScenario(
        PAYOUT_PROVIDER,
        scenarioVersion,
        delay,
        rejectionRate,
        responseLossRate,
        lookupFailureRate,
        lookupDelay,
        seed,
        now);
  }

  /** 거래에 저장할 결과 seed. 같은 시나리오·작업·업무 키면 항상 같은 값이다. */
  public long resultSeed(MockOperation operation, UUID businessKey) {
    String source = seed + ":" + providerId + ":" + operation + ":" + businessKey;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** 저장한 결과 seed로 지연·확정 거절·응답 유실을 정한다. 다시 계산해도 같은 결과다. */
  public PayoutOutcome payoutOutcome(long resultSeed) {
    SplittableRandom random = new SplittableRandom(resultSeed);
    int delayMs = minDelayMs + random.nextInt(maxDelayMs - minDelayMs + 1);
    boolean rejected = random.nextDouble() < rejectionRate.doubleValue();
    boolean responseLost = random.nextDouble() < responseLossRate.doubleValue();
    return new PayoutOutcome(delayMs, rejected, responseLost);
  }

  /** 조회는 거래 결과를 바꾸지 않는 통신 장애라 요청마다 새로 뽑는다. */
  public LookupOutcome lookupOutcome(RandomGenerator random) {
    int delayMs = lookupMinDelayMs + random.nextInt(lookupMaxDelayMs - lookupMinDelayMs + 1);
    boolean failed = random.nextDouble() < lookupFailureRate.doubleValue();
    return new LookupOutcome(delayMs, failed);
  }

  private static BigDecimal requireRate(BigDecimal rate) {
    Objects.requireNonNull(rate, "rate");
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("rate must be between 0 and 1");
    }
    return rate;
  }

  public String getProviderId() {
    return providerId;
  }

  public String getScenarioVersion() {
    return scenarioVersion;
  }

  public record Delay(int minMs, int maxMs) {
    public Delay {
      if (minMs < 0 || maxMs < minMs) {
        throw new IllegalArgumentException("delay must satisfy 0 <= min <= max");
      }
    }
  }

  public record PayoutOutcome(int delayMs, boolean rejected, boolean responseLost) {}

  public record LookupOutcome(int delayMs, boolean failed) {}
}
