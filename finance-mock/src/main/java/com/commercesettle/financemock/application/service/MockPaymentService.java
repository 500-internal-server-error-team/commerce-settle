package com.commercesettle.financemock.application.service;

import com.commercesettle.financemock.application.dto.MockExecutionResult;
import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.application.exception.IdempotencyConflictException;
import com.commercesettle.financemock.application.exception.LookupFailedException;
import com.commercesettle.financemock.application.exception.ScenarioUnavailableException;
import com.commercesettle.financemock.application.exception.TransactionNotFoundException;
import com.commercesettle.financemock.application.port.IdempotencyStore;
import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockPaymentMethod;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.PaymentProvider;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import com.commercesettle.observability.RepeatedWarning;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

/** HTTP 경계에서 service:payment 자격을 검증한 Payment 전용 금융 대역. */
@Service
public class MockPaymentService {
  private static final Logger LOGGER = LoggerFactory.getLogger(MockPaymentService.class);
  private final RepeatedWarning receiptUnavailable = new RepeatedWarning();
  private final PaymentTransactions transactions;
  private final MockScenarioRepository scenarios;

  MockPaymentService(PaymentTransactions transactions, MockScenarioRepository scenarios) {
    this.transactions = transactions;
    this.scenarios = scenarios;
  }

  public MockExecutionResult approve(
      PaymentProvider provider,
      UUID key,
      MockPaymentMethod method,
      String accountRef,
      String fundingRef,
      long amount,
      String version) {
    var scope = scope(provider, MockOperation.APPROVE, key);
    String hash = hash(List.of(method, accountRef, fundingRef, amount, version));
    return execute(
        scope,
        hash,
        () -> {
          MockScenario scenario = scenario(provider, version);
          long seed = scenario.resultSeed(MockOperation.APPROVE, key);
          var outcome = scenario.payoutOutcome(seed);
          var view =
              transactions.approve(
                  scope,
                  hash,
                  provider,
                  key,
                  method,
                  accountRef,
                  fundingRef,
                  amount,
                  scenario,
                  seed,
                  outcome.rejected());
          recordOutcome(view);
          MockPayoutService.pause(outcome.delayMs());
          return new MockExecutionResult(view, outcome.responseLost());
        });
  }

  public MockExecutionResult refund(
      PaymentProvider provider, UUID key, UUID originalId, long amount) {
    var scope = scope(provider, MockOperation.REFUND, key);
    String hash = hash(List.of(originalId, amount));
    return execute(
        scope,
        hash,
        () -> {
          var original = transactions.original(provider, originalId, amount);
          MockScenario scenario = scenario(provider, original.getScenarioVersion());
          long seed = scenario.resultSeed(MockOperation.REFUND, key);
          var outcome = scenario.refundOutcome(seed);
          var view =
              transactions.refund(
                  scope, hash, provider, key, originalId, amount, seed, outcome.rejected());
          recordOutcome(view);
          MockPayoutService.pause(outcome.delayMs());
          return new MockExecutionResult(view, outcome.responseLost());
        });
  }

  public MockTransactionView find(
      PaymentProvider provider, MockOperation operation, UUID key, String version) {
    if (operation == MockOperation.PAYOUT)
      throw new IllegalArgumentException("unsupported operation");
    var outcome = scenario(provider, version).lookupOutcome(ThreadLocalRandom.current());
    MockPayoutService.pause(outcome.delayMs());
    if (outcome.failed()) throw new LookupFailedException("injected lookup failure");
    return transactions
        .find(provider, operation, key)
        .orElseThrow(() -> new TransactionNotFoundException("transaction is not found"));
  }

  public void receiveDue() {
    for (UUID id : transactions.dueReceipts()) {
      try {
        transactions.receive(id);
      } catch (TransientDataAccessException | CannotCreateTransactionException e) {
        receiptUnavailable
            .at(LOGGER)
            .addKeyValue("event", "mock.pg-receipt")
            .addKeyValue("result", "UNKNOWN")
            .log("Receipt storage is temporarily unavailable; remaining receipts continue");
      }
    }
  }

  private static void recordOutcome(MockTransactionView view) {
    var transaction = view.transaction();
    LOGGER
        .atDebug()
        .addKeyValue(
            "event", "mock." + transaction.getOperation().name().toLowerCase(java.util.Locale.ROOT))
        .addKeyValue("providerId", transaction.getProviderId())
        .addKeyValue("result", transaction.getStatus())
        .log("Mock financial result committed");
  }

  private MockExecutionResult execute(
      IdempotencyStore.Scope scope, String hash, Supplier<MockExecutionResult> action) {
    var existing = transactions.replay(scope, hash);
    if (existing.isPresent()) return new MockExecutionResult(existing.get(), false);
    try {
      return action.get();
    } catch (IdempotencyConflictException e) {
      return transactions
          .replay(scope, hash)
          .map(view -> new MockExecutionResult(view, false))
          .orElseThrow(() -> e);
    }
  }

  private MockScenario scenario(PaymentProvider provider, String version) {
    return scenarios
        .find(provider.name(), version)
        .orElseThrow(() -> new ScenarioUnavailableException("payment scenario is not found"));
  }

  private static IdempotencyStore.Scope scope(
      PaymentProvider provider, MockOperation operation, UUID key) {
    return new IdempotencyStore.Scope(
        "SERVICE",
        "payment",
        "MOCK:" + provider + ":" + operation,
        MockPayoutService.sha256(key.toString()));
  }

  private static String hash(List<?> values) {
    return MockPayoutService.sha256(MockPayoutService.fingerprint(values));
  }
}
