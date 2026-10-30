package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.application.dto.DailySettlementSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DailySettlementConfiguration {
  @Bean
  DailySettlementSettings dailySettlementSettings(
      @Value("${settlement.daily-batch.policy-version}") String policyVersion,
      @Value("${settlement.payout.funding-account-ref}") String fundingAccountRef,
      @Value("${settlement.payout.scenario-version}") String scenarioVersion) {
    return new DailySettlementSettings(policyVersion, fundingAccountRef, scenarioVersion);
  }
}
