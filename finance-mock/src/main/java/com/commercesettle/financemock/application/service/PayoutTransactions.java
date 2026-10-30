package com.commercesettle.financemock.application.service;

import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.application.exception.IdempotencyConflictException;
import com.commercesettle.financemock.application.exception.PayoutAccountNotFoundException;
import com.commercesettle.financemock.application.port.IdempotencyStore;
import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.CashEntry;
import com.commercesettle.financemock.domain.model.FundsReservation;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.model.MockTransactionStatus;
import com.commercesettle.financemock.domain.repository.CashEntryRepository;
import com.commercesettle.financemock.domain.repository.FundsReservationRepository;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 지급의 접수와 확정을 각각 한 트랜잭션으로 저장한다. 그 사이의 응답 지연 동안에는 DB 트랜잭션을 열어 두지 않는다. */
@Component
class PayoutTransactions {
  private final IdempotencyStore idempotency;
  private final MockAccountRepository accounts;
  private final MockTransactionRepository transactions;
  private final FundsReservationRepository reservations;
  private final CashEntryRepository cashEntries;
  private final Clock clock;

  PayoutTransactions(
      IdempotencyStore idempotency,
      MockAccountRepository accounts,
      MockTransactionRepository transactions,
      FundsReservationRepository reservations,
      CashEntryRepository cashEntries,
      Clock clock) {
    this.idempotency = idempotency;
    this.accounts = accounts;
    this.transactions = transactions;
    this.reservations = reservations;
    this.cashEntries = cashEntries;
    this.clock = clock;
  }

  /** 같은 업무 키의 기존 거래를 찾는다. 같은 키에 다른 본문이면 금융 효과 없이 충돌로 끝낸다. */
  @Transactional(readOnly = true)
  public Optional<MockTransactionView> replay(IdempotencyStore.Scope scope, String requestHash) {
    return idempotency
        .find(scope)
        .map(
            completed -> {
              if (!completed.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                    "same business key was used with a different request");
              }
              return view(transactions.findById(completed.resourceId()).orElseThrow());
            });
  }

  /** 멱등 기록·거래(PROCESSING)·자금 선점을 함께 저장한다. 재원이 부족하면 아무것도 남기지 않는다. */
  @Transactional
  public MockTransactionView accept(
      IdempotencyStore.Scope scope,
      String requestHash,
      UUID businessKey,
      String accountRef,
      long amount,
      String scenarioVersion,
      long resultSeed) {
    Instant now = clock.instant();
    MockAccount account =
        accounts
            .findForUpdate(MockScenario.PAYOUT_PROVIDER, accountRef, AccountPurpose.PAYOUT)
            .orElseThrow(() -> new PayoutAccountNotFoundException("payout account is not found"));
    MockTransaction transaction =
        MockTransaction.acceptPayout(
            businessKey, account.getId(), amount, scenarioVersion, resultSeed, now);
    idempotency.saveCompleted(UUID.randomUUID(), scope, requestHash, transaction.getId(), 202, now);
    account.reserve(amount, now);
    transactions.save(transaction);
    reservations.save(FundsReservation.reserve(account.getId(), transaction.getId(), amount, now));
    return new MockTransactionView(transaction, account.getAccountRef());
  }

  /**
   * 성공이면 선점을 소비해 잔액을 차감하고 현금 기록을 남긴다. 확정 거절이면 선점만 해제한다. 거래 행을 먼저 잠가, 원요청과 재개가 겹쳐도 한 번만 확정하고 이미 확정된
   * 거래는 바꾸지 않고 돌려준다.
   */
  @Transactional
  public MockTransactionView complete(UUID transactionId, boolean rejected) {
    Instant now = clock.instant();
    MockTransaction transaction = transactions.findByIdForUpdate(transactionId).orElseThrow();
    if (transaction.getStatus() != MockTransactionStatus.PROCESSING) {
      return view(transaction);
    }
    FundsReservation reservation = reservations.findByTransactionId(transactionId).orElseThrow();
    MockAccount account = accounts.findByIdForUpdate(reservation.getAccountId()).orElseThrow();
    if (rejected) {
      transaction.decline(now);
      reservation.release(now);
      account.release(reservation.getAmount(), now);
    } else {
      transaction.succeed(now);
      reservation.consume(now);
      account.consume(reservation.getAmount(), now);
      cashEntries.save(
          CashEntry.payoutDebit(account.getId(), transactionId, reservation.getAmount(), now));
    }
    return new MockTransactionView(transaction, account.getAccountRef());
  }

  @Transactional(readOnly = true)
  public Optional<MockTransactionView> findPayout(UUID businessKey) {
    return transactions
        .findByKey(MockScenario.PAYOUT_PROVIDER, MockOperation.PAYOUT, businessKey)
        .map(this::view);
  }

  private MockTransactionView view(MockTransaction transaction) {
    String accountRef =
        accounts.findById(transaction.getFundingAccountId()).orElseThrow().getAccountRef();
    return new MockTransactionView(transaction, accountRef);
  }
}
