package com.commercesettle.financemock.application.service;

import com.commercesettle.financemock.application.dto.MockAccountView;
import com.commercesettle.financemock.application.dto.MockScenarioView;
import com.commercesettle.financemock.application.exception.IdempotencyConflictException;
import com.commercesettle.financemock.application.exception.MockAccountNotFoundException;
import com.commercesettle.financemock.application.port.IdempotencyStore;
import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.PaymentProvider;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 시험 준비는 추가 전용이다. 리소스와 멱등 완료 키를 같은 짧은 DB 트랜잭션으로 저장한다. */
@Service
public class MockPreparationService {
  private final MockAccountRepository accounts;
  private final MockScenarioRepository scenarios;
  private final IdempotencyStore idempotency;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public MockPreparationService(
      MockAccountRepository accounts,
      MockScenarioRepository scenarios,
      IdempotencyStore idempotency,
      PlatformTransactionManager manager,
      Clock clock) {
    this.accounts = accounts;
    this.scenarios = scenarios;
    this.idempotency = idempotency;
    this.transactions = new TransactionTemplate(manager);
    this.clock = clock;
  }

  public MockAccountView createAccount(
      String caller,
      String key,
      String providerId,
      String accountRef,
      AccountPurpose purpose,
      long balance) {
    var scope = scope(caller, "MOCK:ACCOUNT:CREATE", key);
    String hash = hash(providerId, accountRef, purpose, balance);
    return create(
        scope,
        hash,
        () ->
            MockAccountView.created(
                accounts.save(
                    MockAccount.open(providerId, accountRef, purpose, balance, clock.instant()))),
        id ->
            MockAccountView.created(
                accounts.findById(id).orElseThrow(MockAccountNotFoundException::new)),
        MockAccountView::id);
  }

  public MockAccountView findAccount(UUID id) {
    return transactions.execute(
        status ->
            MockAccountView.current(
                accounts.findById(id).orElseThrow(MockAccountNotFoundException::new)));
  }

  public MockScenarioView createScenario(
      String caller,
      String key,
      String providerId,
      String version,
      MockScenario.Delay delay,
      BigDecimal rejection,
      BigDecimal responseLoss,
      MockScenario.Delay lookupDelay,
      BigDecimal lookupFailure,
      Integer settlementDays,
      Integer refundMin,
      Integer refundMax,
      BigDecimal refundRejection,
      BigDecimal refundLoss,
      long seed) {
    var scope = scope(caller, "MOCK:SCENARIO:CREATE", key);
    String hash =
        hash(
            providerId,
            version,
            delay,
            rejection,
            responseLoss,
            lookupDelay,
            lookupFailure,
            settlementDays,
            refundMin,
            refundMax,
            refundRejection,
            refundLoss,
            seed);
    return create(
        scope,
        hash,
        () -> {
          MockScenario scenario;
          if (MockScenario.PAYOUT_PROVIDER.equals(providerId)) {
            if (settlementDays != null
                || refundMin != null
                || refundMax != null
                || refundRejection != null
                || refundLoss != null) {
              throw new IllegalArgumentException(
                  "payout does not support settlement or refund fields");
            }
            scenario =
                MockScenario.payout(
                    version,
                    delay,
                    rejection,
                    responseLoss,
                    lookupFailure,
                    lookupDelay,
                    seed,
                    clock.instant());
          } else {
            if (settlementDays == null
                || refundMin == null
                || refundMax == null
                || refundRejection == null
                || refundLoss == null) {
              throw new IllegalArgumentException("payment requires settlement and refund fields");
            }
            scenario =
                MockScenario.payment(
                    PaymentProvider.valueOf(providerId),
                    version,
                    delay,
                    rejection,
                    responseLoss,
                    new MockScenario.Delay(refundMin, refundMax),
                    refundRejection,
                    refundLoss,
                    lookupDelay,
                    lookupFailure,
                    settlementDays,
                    seed,
                    clock.instant());
          }
          return MockScenarioView.from(scenarios.save(scenario));
        },
        id ->
            MockScenarioView.from(
                scenarios
                    .find(providerId, version)
                    .filter(scenario -> scenario.getId().equals(id))
                    .orElseThrow()),
        MockScenarioView::id);
  }

  private <T> T create(
      IdempotencyStore.Scope scope,
      String hash,
      Supplier<T> action,
      Function<UUID, T> load,
      Function<T, UUID> identity) {
    try {
      return transactions.execute(
          status -> {
            Optional<T> existing = replay(scope, hash, load);
            if (existing.isPresent()) return existing.get();
            T value = action.get();
            idempotency.saveCompleted(
                UUID.randomUUID(), scope, hash, identity.apply(value), 201, clock.instant());
            return value;
          });
    } catch (IdempotencyConflictException conflict) {
      // 경합한 트랜잭션을 롤백한 뒤 선행 커밋의 원결과를 다시 읽는다.
      return transactions.execute(status -> replay(scope, hash, load).orElseThrow(() -> conflict));
    }
  }

  private <T> Optional<T> replay(
      IdempotencyStore.Scope scope, String hash, Function<UUID, T> load) {
    return idempotency
        .find(scope)
        .map(
            completed -> {
              if (!completed.requestHash().equals(hash)) {
                throw new IdempotencyConflictException(
                    "same preparation key has a different request");
              }
              return load.apply(completed.resourceId());
            });
  }

  private static IdempotencyStore.Scope scope(String caller, String operation, String key) {
    return new IdempotencyStore.Scope("OPERATOR", caller, operation, MockPayoutService.sha256(key));
  }

  private static String hash(Object... values) {
    var normalized =
        Arrays.stream(values)
            .map(
                value ->
                    value instanceof BigDecimal rate
                        ? rate.stripTrailingZeros().toPlainString()
                        : value)
            .toList();
    return MockPayoutService.sha256(MockPayoutService.fingerprint(normalized));
  }
}
