package com.commercesettle.settlement.application.service;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.event.PurchaseConfirmedPayload;
import com.commercesettle.settlement.application.exception.WaitingInboxFailedException;
import com.commercesettle.settlement.application.port.InboxStore;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.domain.model.InboxReviewReason;
import com.commercesettle.settlement.domain.model.SettlementAmounts;
import com.commercesettle.settlement.domain.model.SettlementEntry;
import com.commercesettle.settlement.domain.model.SettlementItem;
import com.commercesettle.settlement.domain.repository.SettlementEntryRepository;
import com.commercesettle.settlement.domain.repository.SettlementItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제확정·구매확정 이벤트를 Inbox 저장과 같은 트랜잭션에 반영한다. 선행 결제가 없는 구매확정만 WAITING으로 남기고 처리기가 다시 판정한다. 같은 결제의 다른
 * eventId는 기존 값과 같으면 기존 결과를 유지하고, 다르면 운영 확인으로 남긴다.
 */
@Service
public class SettlementEventService {
  private static final Logger LOGGER = LoggerFactory.getLogger(SettlementEventService.class);
  // policies.md 재처리: 최대 10회, 1초부터 최대 60초 간격, 소진 시 REVIEW_REQUIRED.
  private static final int MAX_WAITING_ATTEMPTS = 10;
  private static final Duration MIN_FAILURE_DELAY = Duration.ofSeconds(1);

  @Value("${app.demo.enabled:false}")
  private boolean demoEnabled;

  private AsyncObservation observations = AsyncObservation.NOOP;
  private final InboxStore inbox;
  private final SettlementItemRepository items;
  private final SettlementEntryRepository entries;
  private final RefundEventService refunds;
  private final Clock clock;
  private final Duration waitingRecheckDelay;
  private final TransactionTemplate transactions;

  SettlementEventService(
      InboxStore inbox,
      SettlementItemRepository items,
      SettlementEntryRepository entries,
      RefundEventService refunds,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${settlement.inbox-processor.interval-ms}") long waitingRecheckMillis) {
    this.inbox = inbox;
    this.items = items;
    this.entries = entries;
    this.refunds = refunds;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.waitingRecheckDelay = Duration.ofMillis(waitingRecheckMillis);
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.observations = observation;
  }

  @Transactional
  public void receivePaymentConfirmed(EventEnvelope<PaymentConfirmedPayload> event, Source source) {
    Optional<UUID> inboxId = inbox.receive(event, source);
    if (inboxId.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    PaymentConfirmedPayload payment = event.payload();
    if (!payment.amountsMatchContract()) {
      review(inboxId.get(), event, "AMOUNT_CONTRACT_MISMATCH", now);
      return;
    }
    SettlementAmounts amounts =
        new SettlementAmounts(
            payment.totalAmount(),
            payment.platformCouponAmount(),
            payment.settlementBaseAmount(),
            payment.externalAmount(),
            payment.mileageAmount(),
            payment.feeAmount(),
            payment.sellerNetAmount());
    Optional<SettlementItem> existing = items.findByPaymentId(payment.paymentId());
    if (existing.isPresent()) {
      if (sameFact(existing.get(), payment, amounts, recordedPolicyVersion(payment.paymentId()))) {
        inbox.markApplied(inboxId.get(), existing.get().getId(), now);
      } else {
        review(inboxId.get(), event, "PAYMENT_ALREADY_RECORDED_WITH_DIFFERENT_VALUES", now);
      }
      return;
    }
    SettlementItem item =
        items.save(
            SettlementItem.create(
                payment.paymentId(),
                payment.orderId(),
                payment.productId(),
                payment.sellerId(),
                amounts,
                payment.orderVersion(),
                now));
    entries.append(
        SettlementEntry.initial(
            item, event.eventId(), event.occurredAt().toInstant(), payment.policyVersion(), now));
    inbox.markApplied(inboxId.get(), item.getId(), now);
    // 대기를 소진한 구매확정은 원인(결제 미도착)이 사라졌으므로 처리기가 다시 판정하게 한다.
    for (UUID revived :
        inbox.reviveExhaustedPurchases(payment.paymentId(), MAX_WAITING_ATTEMPTS, now)) {
      LOGGER
          .atDebug()
          .addKeyValue("event", "inbox.waiting-revived")
          .addKeyValue("inboxId", revived)
          .addKeyValue("paymentId", payment.paymentId())
          .log("Exhausted purchase confirmation is waiting again after a late payment");
    }
  }

  @Transactional
  public void receivePurchaseConfirmed(
      EventEnvelope<PurchaseConfirmedPayload> event, Source source) {
    Optional<UUID> inboxId = inbox.receive(event, source);
    if (inboxId.isPresent()) {
      applyPurchase(inboxId.get(), event);
    }
  }

  /**
   * WAITING 구매확정이나 환불 예약 요청 하나를 다시 판정한다. 대기 중인 건이 없으면 false. 선점한 행의 판정이 커밋 flush까지 포함해 실패하면 롤백 뒤 그
   * 행 ID를 담은 {@link WaitingInboxFailedException}을 던진다. 롤백된 행은 기한이 지난 WAITING 그대로라 처리기가 별도 트랜잭션에서
   * 미룬다.
   */
  public boolean processNextWaiting() {
    AtomicReference<UUID> claimed = new AtomicReference<>();
    try {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                Optional<InboxStore.Pending> waiting = inbox.claimNextWaiting();
                if (waiting.isEmpty()) {
                  return false;
                }
                claimed.set(waiting.get().inboxId());
                process(waiting.get());
                return true;
              }));
    } catch (RuntimeException failure) {
      if (claimed.get() == null) {
        throw failure;
      }
      throw new WaitingInboxFailedException(claimed.get(), failure);
    }
  }

  /** 재판정이 실패한 WAITING 행의 다음 판정을 재판정 주기(최소 1초) 뒤로 미룬다. 실패한 처리와 별도 트랜잭션이다. */
  @Transactional
  public boolean deferFailedWaiting(UUID inboxId) {
    Duration delay =
        waitingRecheckDelay.compareTo(MIN_FAILURE_DELAY) < 0
            ? MIN_FAILURE_DELAY
            : waitingRecheckDelay;
    return inbox.deferWaiting(inboxId, delay, clock.instant());
  }

  /** 고정한 버전·사유만 재판정한다. 선행 근거가 없으면 원래 상태를 유지한다. */
  @Transactional
  public String rejudge(UUID inboxId, long expectedVersion, String expectedReason) {
    var found = inbox.lockReview(inboxId);
    if (found.isEmpty()
        || found.get().version() != expectedVersion
        || !"REVIEW_REQUIRED".equals(found.get().status())) return "VERSION_CHANGED";
    var row = found.get();
    if (!java.util.Objects.equals(expectedReason, row.reasonCode())
        || InboxReviewReason.categoryOf(row.reasonCode())
            != InboxReviewReason.Category.ORDER_DEPENDENT) return "NOT_REJUDGEABLE";
    boolean ready;
    if (row.event() instanceof InboxStore.Pending.Purchase p) {
      ready =
          items
              .findByPaymentId(p.event().payload().paymentId())
              .filter(
                  item ->
                      "PAYMENT_NOT_ARRIVED".equals(expectedReason)
                          || ("PURCHASE_DURING_REFUND".equals(expectedReason)
                              && item.getStatus()
                                  != com.commercesettle.settlement.domain.model.SettlementItemStatus
                                      .REFUND_RESERVED
                              && item.getStatus()
                                  != com.commercesettle.settlement.domain.model.SettlementItemStatus
                                      .REFUNDED))
              .isPresent();
    } else {
      ready = refunds.prerequisiteAvailable(row.event(), expectedReason);
    }
    if (!ready) return "PREREQUISITE_MISSING";
    inbox.reopenReview(inboxId, clock.instant());
    process(row.event());
    return inbox.lockReview(inboxId).orElseThrow().status();
  }

  private void process(InboxStore.Pending pending) {
    var scope =
        observations.start(
            "settlement.inbox.process",
            pending.traceParent(),
            Map.of("inbox.id", pending.inboxId().toString()),
            pending.timing());
    try {
      processObserved(pending);
    } catch (RuntimeException | Error failure) {
      scope.error(failure);
      throw failure;
    } finally {
      observations.closeAfterTransaction(scope);
    }
  }

  private void processObserved(InboxStore.Pending pending) {
    switch (pending) {
      case InboxStore.Pending.Purchase p -> applyPurchase(p.inboxId(), p.event());
      case InboxStore.Pending.RefundReservation p ->
          refunds.processWaitingReservation(p.inboxId(), p.event());
      case InboxStore.Pending.RefundCompleted p ->
          refunds.processFinancialCompleted(p.inboxId(), p.event());
      case InboxStore.Pending.RefundRelease p ->
          refunds.processReleaseRequested(p.inboxId(), p.event());
    }
  }

  private void applyPurchase(UUID inboxId, EventEnvelope<PurchaseConfirmedPayload> event) {
    Instant now = clock.instant();
    PurchaseConfirmedPayload purchase = event.payload();
    if (!purchase.timesMatchContract(demoEnabled)) {
      review(inboxId, event, "PURCHASE_TIME_CONTRACT_MISMATCH", now);
      return;
    }
    Optional<SettlementItem> found = items.findByPaymentId(purchase.paymentId());
    if (found.isEmpty()) {
      // 결제 원장이 아직 없다. 두 토픽 사이 순서는 보장되지 않는다.
      if (!inbox.markWaiting(inboxId, waitingRecheckDelay, MAX_WAITING_ATTEMPTS, now)) {
        logReview(event, "PAYMENT_NOT_ARRIVED");
      }
      return;
    }
    SettlementItem item = found.get();
    if (!item.getOrderId().equals(purchase.orderId())
        || purchase.orderVersion() < item.getOrderVersion()) {
      review(inboxId, event, "PURCHASE_DOES_NOT_MATCH_PAYMENT", now);
      return;
    }
    Instant confirmedAt = purchase.confirmedAt().toInstant();
    switch (item.getStatus()) {
      case WAITING_CONFIRMATION -> {
        // 결제 원장과 구매확정이 모두 준비된 시각은 지금이다.
        item.markPayable(confirmedAt, now, now);
        inbox.markApplied(inboxId, item.getId(), now);
      }
      case PAYABLE, PAYOUT_RESERVED, PAID -> {
        if (confirmedAt.truncatedTo(ChronoUnit.MICROS).equals(item.getPurchaseConfirmedAt())) {
          inbox.markApplied(inboxId, item.getId(), now);
        } else {
          review(inboxId, event, "PURCHASE_ALREADY_RECORDED_WITH_DIFFERENT_VALUES", now);
        }
      }
      // 계약상 오지 않는다. Commerce는 진행 중인 환불이 있으면 구매확정하지 않고, 해제 뒤에는 항목이 원상태로 돌아와 있다.
      case REFUND_RESERVED, REFUNDED -> review(inboxId, event, "PURCHASE_DURING_REFUND", now);
    }
  }

  /** 항목에는 정책 버전이 없으므로 최초 원장(상쇄 줄 제외)의 값을 쓴다. 최초 5 leg는 같은 값이다. */
  private String recordedPolicyVersion(UUID paymentId) {
    return entries.findByPaymentId(paymentId).stream()
        .filter(entry -> entry.getReversalOf() == null)
        .map(SettlementEntry::getPolicyVersion)
        .findFirst()
        .orElse(null);
  }

  private static boolean sameFact(
      SettlementItem item,
      PaymentConfirmedPayload payment,
      SettlementAmounts amounts,
      String recordedPolicyVersion) {
    return item.getAmounts().equals(amounts)
        && payment.policyVersion().equals(recordedPolicyVersion)
        && item.getSellerId().equals(payment.sellerId())
        && item.getProductId().equals(payment.productId())
        && item.getOrderId().equals(payment.orderId())
        && item.getOrderVersion() == payment.orderVersion();
  }

  private void review(UUID inboxId, EventEnvelope<?> event, String reason, Instant now) {
    inbox.markReviewRequired(inboxId, InboxReviewReason.valueOf(reason), now);
    logReview(event, reason);
  }

  private static void logReview(EventEnvelope<?> event, String reason) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "settlement.inbox")
        .addKeyValue("eventId", event.eventId())
        .addKeyValue("eventType", event.eventType())
        .addKeyValue("result", "REVIEW_REQUIRED")
        .addKeyValue("reason", reason)
        .log("Settlement event requires review");
  }
}
