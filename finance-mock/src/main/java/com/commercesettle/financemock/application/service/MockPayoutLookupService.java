package com.commercesettle.financemock.application.service;

import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.application.exception.LookupFailedException;
import com.commercesettle.financemock.application.exception.ScenarioUnavailableException;
import com.commercesettle.financemock.application.exception.TransactionNotFoundException;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * 원지급 조회. 요청한 조회 시나리오의 지연·장애만 적용하며 저장된 금융 결과는 바꾸지 않는다. 시나리오 부재와 주입한 장애는 거래 부재와 구분한다. 확정 전에 멈춘 거래는
 * Worker가 조회만 반복하므로 여기서도 확정을 재개한다.
 */
@Service
public class MockPayoutLookupService {
  private final PayoutTransactions transactions;
  private final MockScenarioRepository scenarios;
  private final MockPayoutService payouts;

  MockPayoutLookupService(
      PayoutTransactions transactions,
      MockScenarioRepository scenarios,
      MockPayoutService payouts) {
    this.transactions = transactions;
    this.scenarios = scenarios;
    this.payouts = payouts;
  }

  public MockTransactionView find(UUID businessKey, String scenarioVersion) {
    MockScenario scenario =
        scenarios
            .find(MockScenario.PAYOUT_PROVIDER, scenarioVersion)
            .orElseThrow(() -> new ScenarioUnavailableException("lookup scenario is not found"));
    MockScenario.LookupOutcome outcome = scenario.lookupOutcome(ThreadLocalRandom.current());
    MockPayoutService.pause(outcome.delayMs());
    if (outcome.failed()) {
      throw new LookupFailedException("injected lookup failure");
    }
    return payouts.resume(
        transactions
            .findPayout(businessKey)
            .orElseThrow(() -> new TransactionNotFoundException("payout is not found")));
  }
}
