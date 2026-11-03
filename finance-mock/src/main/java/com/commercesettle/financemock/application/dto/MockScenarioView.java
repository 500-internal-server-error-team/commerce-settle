package com.commercesettle.financemock.application.dto;

import com.commercesettle.financemock.domain.model.MockScenario;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record MockScenarioView(
    UUID id,
    String providerId,
    String scenarioVersion,
    int minDelayMs,
    int maxDelayMs,
    BigDecimal rejectionRate,
    BigDecimal responseLossRate,
    int lookupMinDelayMs,
    int lookupMaxDelayMs,
    BigDecimal lookupFailureRate,
    Integer settlementDelayBusinessDays,
    Integer refundMinDelayMs,
    Integer refundMaxDelayMs,
    BigDecimal refundRejectionRate,
    BigDecimal refundResponseLossRate,
    long seed,
    OffsetDateTime createdAt) {
  public MockScenarioView {
    rejectionRate = rejectionRate.stripTrailingZeros();
    responseLossRate = responseLossRate.stripTrailingZeros();
    lookupFailureRate = lookupFailureRate.stripTrailingZeros();
    refundRejectionRate =
        refundRejectionRate == null ? null : refundRejectionRate.stripTrailingZeros();
    refundResponseLossRate =
        refundResponseLossRate == null ? null : refundResponseLossRate.stripTrailingZeros();
  }

  public static MockScenarioView from(MockScenario scenario) {
    return new MockScenarioView(
        scenario.getId(),
        scenario.getProviderId(),
        scenario.getScenarioVersion(),
        scenario.getMinDelayMs(),
        scenario.getMaxDelayMs(),
        scenario.getRejectionRate(),
        scenario.getResponseLossRate(),
        scenario.getLookupMinDelayMs(),
        scenario.getLookupMaxDelayMs(),
        scenario.getLookupFailureRate(),
        MockScenario.PAYOUT_PROVIDER.equals(scenario.getProviderId())
            ? null
            : scenario.getSettlementDelayBusinessDays(),
        scenario.getRefundMinDelayMs(),
        scenario.getRefundMaxDelayMs(),
        scenario.getRefundRejectionRate(),
        scenario.getRefundResponseLossRate(),
        scenario.getSeed(),
        scenario.getCreatedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
  }
}
