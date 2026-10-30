package com.commercesettle.financemock.application.service;

import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.application.dto.PayoutResult;
import com.commercesettle.financemock.application.exception.IdempotencyConflictException;
import com.commercesettle.financemock.application.exception.ScenarioUnavailableException;
import com.commercesettle.financemock.application.port.IdempotencyStore;
import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.model.MockTransactionStatus;
import com.commercesettle.financemock.domain.repository.MockScenarioRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 모의 지급 실행. 같은 업무 키는 장애 확률을 다시 적용하기 전에 기존 거래를 돌려준다. 신규 지급은 접수(자금 선점) → 확정(차감 또는 해제) → 응답 지연 순서로
 * 처리해, 지연·응답 유실 모두 결과를 저장한 뒤 응답에만 적용한다. 확정 전에 멈춘 거래는 재요청·조회에서 저장된 seed로 확정을 재개한다.
 */
@Service
public class MockPayoutService {
  private static final String ACTOR_KIND = "SERVICE";

  /** 서비스 자격 검증 전까지 발신 서비스를 지급 호출자로 고정한다. */
  private static final String ACTOR_ID = "settlement";

  private static final String OPERATION = "MOCK:PAYOUT:PAYOUT";

  private final PayoutTransactions transactions;
  private final MockScenarioRepository scenarios;

  MockPayoutService(PayoutTransactions transactions, MockScenarioRepository scenarios) {
    this.transactions = transactions;
    this.scenarios = scenarios;
  }

  public PayoutResult execute(
      UUID businessKey,
      String accountRef,
      String sellerAccountRef,
      long amount,
      String scenarioVersion) {
    IdempotencyStore.Scope scope =
        new IdempotencyStore.Scope(ACTOR_KIND, ACTOR_ID, OPERATION, sha256(businessKey.toString()));
    String requestHash =
        sha256(fingerprint(List.of(accountRef, sellerAccountRef, amount, scenarioVersion)));

    Optional<MockTransactionView> existing = transactions.replay(scope, requestHash);
    if (existing.isPresent()) {
      return new PayoutResult(resume(existing.get()), false);
    }

    MockScenario scenario =
        scenarios
            .find(MockScenario.PAYOUT_PROVIDER, scenarioVersion)
            .orElseThrow(() -> new ScenarioUnavailableException("payout scenario is not found"));
    long resultSeed = scenario.resultSeed(MockOperation.PAYOUT, businessKey);
    MockScenario.PayoutOutcome outcome = scenario.payoutOutcome(resultSeed);

    MockTransactionView accepted;
    try {
      accepted =
          transactions.accept(
              scope, requestHash, businessKey, accountRef, amount, scenarioVersion, resultSeed);
    } catch (IdempotencyConflictException e) {
      // 같은 키의 다른 요청이 먼저 커밋했다. 같은 본문이면 그 거래를 돌려준다.
      return transactions
          .replay(scope, requestHash)
          .map(view -> new PayoutResult(resume(view), false))
          .orElseThrow(() -> e);
    }

    MockTransactionView completed =
        transactions.complete(accepted.transaction().getId(), outcome.rejected());
    pause(outcome.delayMs());
    return new PayoutResult(completed, outcome.responseLost());
  }

  /** PROCESSING이면 저장된 시나리오 버전·seed로 같은 결과를 계산해 확정한다. 지연·응답 유실은 다시 적용하지 않는다. */
  MockTransactionView resume(MockTransactionView view) {
    MockTransaction transaction = view.transaction();
    if (transaction.getStatus() != MockTransactionStatus.PROCESSING) {
      return view;
    }
    MockScenario scenario =
        scenarios
            .find(MockScenario.PAYOUT_PROVIDER, transaction.getScenarioVersion())
            .orElseThrow(() -> new ScenarioUnavailableException("payout scenario is not found"));
    boolean rejected = scenario.payoutOutcome(transaction.getResultSeed()).rejected();
    return transactions.complete(transaction.getId(), rejected);
  }

  static void pause(int delayMs) {
    if (delayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      // 접수한 거래는 끝까지 확정한다. 중단 신호만 보존한다.
      Thread.currentThread().interrupt();
    }
  }

  /** 길이를 앞에 붙여 값의 경계가 섞이지 않게 한다. */
  static String fingerprint(List<?> values) {
    StringBuilder text = new StringBuilder();
    for (Object value : values) {
      String part = String.valueOf(value);
      text.append(part.length()).append(':').append(part).append(';');
    }
    return text.toString();
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
