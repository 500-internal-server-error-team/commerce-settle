package com.commercesettle.financemock.infrastructure.config;

import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/** 설정한 지급 재원 계정과 정상 시나리오가 없을 때만 만든다. 이미 있으면 잔액과 설정을 바꾸지 않는다. */
@Configuration
@EnableConfigurationProperties(PayoutFixtureProperties.class)
@ConditionalOnProperty(prefix = "finance-mock.payout-fixture", name = "account-ref")
class PayoutFixtureConfiguration {
  @Bean
  ApplicationRunner payoutFixture(
      PayoutFixtureProperties fixture,
      MockAccountRepository accounts,
      MockScenarioRepository scenarios,
      TransactionOperations transactions,
      Clock clock) {
    return args ->
        transactions.executeWithoutResult(
            status -> {
              if (accounts
                  .find(MockScenario.PAYOUT_PROVIDER, fixture.accountRef(), AccountPurpose.PAYOUT)
                  .isEmpty()) {
                accounts.save(
                    MockAccount.open(
                        MockScenario.PAYOUT_PROVIDER,
                        fixture.accountRef(),
                        AccountPurpose.PAYOUT,
                        fixture.openingBalance(),
                        clock.instant()));
              }
              if (scenarios
                  .find(MockScenario.PAYOUT_PROVIDER, fixture.scenarioVersion())
                  .isEmpty()) {
                scenarios.save(
                    MockScenario.payout(
                        fixture.scenarioVersion(),
                        new MockScenario.Delay(0, 0),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new MockScenario.Delay(0, 0),
                        fixture.seed(),
                        clock.instant()));
              }
            });
  }
}
