package com.commercesettle.financemock.application.service;

import com.commercesettle.financemock.application.dto.MockTransactionView;
import com.commercesettle.financemock.application.exception.IdempotencyConflictException;
import com.commercesettle.financemock.application.exception.TransactionNotFoundException;
import com.commercesettle.financemock.application.port.IdempotencyStore;
import com.commercesettle.financemock.domain.model.AccountPurpose;
import com.commercesettle.financemock.domain.model.CashEntry;
import com.commercesettle.financemock.domain.model.FundsReservation;
import com.commercesettle.financemock.domain.model.MockAccount;
import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.MockPaymentMethod;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.domain.model.MockTransaction;
import com.commercesettle.financemock.domain.model.PaymentProvider;
import com.commercesettle.financemock.domain.repository.CashEntryRepository;
import com.commercesettle.financemock.domain.repository.FundsReservationRepository;
import com.commercesettle.financemock.domain.repository.MockAccountRepository;
import com.commercesettle.financemock.domain.repository.MockTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 금융 변경만 짧게 커밋한다. 응답 지연·유실·조회 장애는 이 경계 밖에서 적용한다. */
@Component
class PaymentTransactions {
  private final IdempotencyStore idempotency;
  private final MockAccountRepository accounts;
  private final MockTransactionRepository transactions;
  private final FundsReservationRepository reservations;
  private final CashEntryRepository cashEntries;
  private final Clock clock;
  private final String calendarVersion;
  private final List<LocalDate> holidays;

  PaymentTransactions(
      IdempotencyStore idempotency,
      MockAccountRepository accounts,
      MockTransactionRepository transactions,
      FundsReservationRepository reservations,
      CashEntryRepository cashEntries,
      Clock clock,
      @Value("${finance-mock.pg.calendar-version:weekdays-v1}") String calendarVersion,
      @Value("${finance-mock.pg.holidays:}") List<String> holidays) {
    this.idempotency = idempotency;
    this.accounts = accounts;
    this.transactions = transactions;
    this.reservations = reservations;
    this.cashEntries = cashEntries;
    this.clock = clock;
    this.calendarVersion = calendarVersion;
    this.holidays = holidays.stream().map(LocalDate::parse).toList();
  }

  @Transactional(readOnly = true)
  public Optional<MockTransactionView> replay(IdempotencyStore.Scope scope, String hash) {
    return idempotency
        .find(scope)
        .map(
            record -> {
              if (!record.requestHash().equals(hash)) {
                throw new IdempotencyConflictException(
                    "different request for the same business key");
              }
              return view(transactions.findById(record.resourceId()).orElseThrow());
            });
  }

  @Transactional
  public MockTransactionView approve(
      IdempotencyStore.Scope scope,
      String hash,
      PaymentProvider provider,
      UUID key,
      MockPaymentMethod method,
      String accountRef,
      String fundingRef,
      long amount,
      MockScenario scenario,
      long seed,
      boolean rejected) {
    provider.requireMethod(method);
    Instant now = clock.instant();
    MockAccount funding =
        accounts
            .find(MockScenario.PAYOUT_PROVIDER, fundingRef, AccountPurpose.PAYOUT)
            .orElseThrow(() -> new IllegalArgumentException("funding account is not registered"));
    MockAccount wallet =
        method == MockPaymentMethod.EASY_PAY
            ? accounts
                .findForUpdate(provider.name(), accountRef, AccountPurpose.WALLET)
                .orElseThrow(() -> new IllegalArgumentException("wallet is not registered"))
            : null;
    MockTransaction approval =
        MockTransaction.approve(
            provider,
            key,
            method,
            wallet == null ? null : wallet.getId(),
            funding.getId(),
            amount,
            scenario.getScenarioVersion(),
            seed,
            now);
    idempotency.saveCompleted(UUID.randomUUID(), scope, hash, approval.getId(), 202, now);
    FundsReservation reservation = null;
    boolean insufficient =
        wallet != null && wallet.getBalance() - wallet.getReservedAmount() < amount;
    if (wallet != null && !insufficient) {
      wallet.reserve(amount, now);
      reservation = FundsReservation.reserve(wallet.getId(), approval.getId(), amount, now);
    }
    if (rejected || insufficient) {
      approval.decline(now);
      if (reservation != null) {
        wallet.release(amount, now);
        reservation.release(now);
      }
    } else {
      approval.succeed(now);
      approval.scheduleReceipt(
          scenario.getSettlementDelayBusinessDays(), calendarVersion, holidays);
      if (reservation != null) {
        wallet.consume(amount, now);
        reservation.consume(now);
      }
    }
    transactions.save(approval);
    if (reservation != null) reservations.save(reservation);
    return new MockTransactionView(approval, fundingRef);
  }

  @Transactional(readOnly = true)
  public MockTransaction original(PaymentProvider provider, UUID id, long amount) {
    MockTransaction original =
        transactions
            .findById(id)
            .filter(t -> t.getProviderId().equals(provider.name()))
            .orElseThrow(() -> new TransactionNotFoundException("original approval is not found"));
    original.requireRefundable(provider, amount);
    return original;
  }

  @Transactional
  public MockTransactionView refund(
      IdempotencyStore.Scope scope,
      String hash,
      PaymentProvider provider,
      UUID key,
      UUID originalId,
      long amount,
      long seed,
      boolean rejected) {
    Instant now = clock.instant();
    MockTransaction original =
        transactions
            .findForUpdate(originalId)
            .filter(t -> t.getProviderId().equals(provider.name()))
            .orElseThrow(() -> new TransactionNotFoundException("original approval is not found"));
    MockTransaction refund = original.refund(provider, key, amount, seed, now);
    if (transactions.hasActiveRefund(originalId)) {
      throw new IdempotencyConflictException("original approval already has an active refund");
    }
    idempotency.saveCompleted(UUID.randomUUID(), scope, hash, refund.getId(), 202, now);
    if (rejected) {
      refund.decline(now);
    } else {
      if (original.getPgReceivedAt() == null) {
        original.receive(0, now);
      } else if (original.getPgReceivedAmount() > 0) {
        MockAccount funding = funding(original.getFundingAccountId());
        funding.reserve(amount, now);
        funding.consume(amount, now);
        cashEntries.save(CashEntry.refundDebit(funding.getId(), refund.getId(), amount, now));
      }
      if (original.getMethod() == MockPaymentMethod.EASY_PAY) {
        MockAccount wallet = accounts.findForUpdate(original.getAccountId()).orElseThrow();
        if (!wallet.getProviderId().equals(provider.name())
            || wallet.getPurpose() != AccountPurpose.WALLET) {
          throw new IllegalStateException("original wallet does not match");
        }
        wallet.credit(amount, now);
      }
      refund.succeed(now);
    }
    transactions.save(refund);
    return view(refund);
  }

  @Transactional(readOnly = true)
  public Optional<MockTransactionView> find(
      PaymentProvider provider, MockOperation operation, UUID key) {
    return transactions.findByKey(provider.name(), operation, key).map(this::view);
  }

  @Transactional
  public List<UUID> dueReceipts() {
    return transactions.findDueReceipts(clock.instant(), 100);
  }

  @Transactional
  public void receive(UUID approvalId) {
    Instant now = clock.instant();
    MockTransaction approval = transactions.findForReceiptUpdate(approvalId).orElse(null);
    if (approval == null || !approval.receiptDue(now)) return;
    MockAccount funding = funding(approval.getFundingAccountId());
    funding.credit(approval.getAmount(), now);
    cashEntries.save(CashEntry.pgReceipt(funding.getId(), approvalId, approval.getAmount(), now));
    approval.receive(approval.getAmount(), now);
  }

  private MockAccount funding(UUID id) {
    MockAccount account = accounts.findForUpdate(id).orElseThrow();
    if (!account.getProviderId().equals(MockScenario.PAYOUT_PROVIDER)
        || account.getPurpose() != AccountPurpose.PAYOUT) {
      throw new IllegalStateException("original funding account does not match");
    }
    return account;
  }

  private MockTransactionView view(MockTransaction transaction) {
    return new MockTransactionView(
        transaction,
        accounts.findById(transaction.getFundingAccountId()).orElseThrow().getAccountRef());
  }
}
