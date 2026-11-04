package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.exception.InboxOperationNotFoundException;
import com.commercesettle.settlement.application.exception.OperationRejectedException;
import com.commercesettle.settlement.application.exception.OperationRejectedException.Reason;
import com.commercesettle.settlement.application.port.AuditLog;
import com.commercesettle.settlement.application.port.InboxOperationStore;
import com.commercesettle.settlement.application.port.InboxOperationStore.AttemptView;
import com.commercesettle.settlement.application.port.InboxOperationStore.AuditView;
import com.commercesettle.settlement.application.port.InboxOperationStore.Claim;
import com.commercesettle.settlement.application.port.InboxOperationStore.ExpectedTarget;
import com.commercesettle.settlement.application.port.InboxOperationStore.Filter;
import com.commercesettle.settlement.application.port.InboxOperationStore.InboxView;
import com.commercesettle.settlement.application.port.InboxOperationStore.RunView;
import com.commercesettle.settlement.application.port.InboxOperationStore.Summary;
import com.commercesettle.settlement.application.port.InboxOperationStore.TargetView;
import com.commercesettle.settlement.domain.model.InboxReviewReason;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxOperationService {
  private static final Set<String> TYPES =
      Set.of(
          "PaymentConfirmed",
          "PurchaseConfirmed",
          "RefundReservationRequested",
          "RefundFinancialCompleted",
          "RefundReleaseRequested");
  private final InboxOperationStore store;
  private final IdempotentRequests idempotent;
  private final AuditLog audit;
  private final Clock clock;
  private final SettlementEventService events;

  public InboxOperationService(
      InboxOperationStore store,
      IdempotentRequests idempotent,
      AuditLog audit,
      Clock clock,
      SettlementEventService events) {
    this.store = store;
    this.idempotent = idempotent;
    this.audit = audit;
    this.clock = clock;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<Summary> summary() {
    return store.summary();
  }

  @Transactional(readOnly = true)
  public PageView<InboxView> list(Filter filter, int page, int size) {
    page(page, size);
    filter(filter);
    return store.list(filter, page, size);
  }

  @Transactional(readOnly = true)
  public InboxView get(UUID id) {
    return store.find(id).orElseThrow(InboxOperationNotFoundException::new);
  }

  @Transactional(readOnly = true)
  public RunView run(UUID id) {
    return store.findRun(id).orElseThrow(InboxOperationNotFoundException::new);
  }

  @Transactional(readOnly = true)
  public PageView<RunView> runs(int page, int size) {
    page(page, size);
    return store.runs(page, size);
  }

  @Transactional(readOnly = true)
  public PageView<TargetView> targets(UUID id, int page, int size) {
    page(page, size);
    run(id);
    return store.targets(id, page, size);
  }

  @Transactional(readOnly = true)
  public PageView<AttemptView> attempts(UUID id, int page, int size) {
    page(page, size);
    run(id);
    return store.attempts(id, page, size);
  }

  @Transactional(readOnly = true)
  public PageView<AuditView> audits(UUID id, int page, int size) {
    page(page, size);
    run(id);
    return store.audits(id, page, size);
  }

  @Transactional
  public RunView create(
      String operator,
      String key,
      Filter filter,
      int expectedCount,
      List<ExpectedTarget> targets,
      String reasonCode) {
    filter(filter);
    reason(reasonCode);
    if (InboxReviewReason.categoryOf(filter.reasonCode())
        != InboxReviewReason.Category.ORDER_DEPENDENT)
      throw new OperationRejectedException(Reason.INBOX_NOT_REJUDGEABLE);
    if (expectedCount < 1
        || expectedCount > 100
        || targets == null
        || targets.size() != expectedCount
        || targets.stream()
            .anyMatch(t -> t == null || t.inboxId() == null || t.expectedVersion() < 0)
        || new HashSet<>(targets.stream().map(ExpectedTarget::inboxId).toList()).size()
            != targets.size()) throw new IllegalArgumentException("Invalid targets");
    var fixed =
        targets.stream()
            .sorted(java.util.Comparator.comparing(t -> t.inboxId().toString()))
            .toList();
    return idempotent.execute(
        new IdempotentRequests.Request(
            "OPERATOR",
            operator,
            "INBOX_REJUDGEMENT_CREATE",
            key,
            List.of(filter, expectedCount, fixed, reasonCode)),
        202,
        () -> {
          var created = store.create(filter, fixed, expectedCount, operator, reasonCode);
          record(
              operator,
              AuditLog.Action.INBOX_REJUDGEMENT_CREATED,
              created.id(),
              null,
              "RUNNING",
              reasonCode,
              created.id());
          return created;
        },
        RunView::id,
        this::run);
  }

  @Transactional
  public RunView transition(
      String operator,
      UUID id,
      String key,
      long expectedVersion,
      String action,
      String reasonCode) {
    reason(reasonCode);
    if (expectedVersion < 0) throw new IllegalArgumentException("Invalid version");
    return idempotent.execute(
        new IdempotentRequests.Request(
            "OPERATOR",
            operator,
            "INBOX_REJUDGEMENT_" + action,
            key,
            List.of(id, expectedVersion, reasonCode)),
        202,
        () -> {
          var before = run(id);
          store.transition(id, expectedVersion, action);
          var after = run(id);
          var auditAction =
              switch (action) {
                case "PAUSE" -> AuditLog.Action.INBOX_REJUDGEMENT_PAUSED;
                case "RESUME" -> AuditLog.Action.INBOX_REJUDGEMENT_RESUMED;
                case "CANCEL" -> AuditLog.Action.INBOX_REJUDGEMENT_CANCELLED;
                default -> throw new IllegalArgumentException("Invalid action");
              };
          record(operator, auditAction, id, before.status(), after.status(), reasonCode, id);
          return after;
        },
        RunView::id,
        this::run);
  }

  @Transactional
  public Optional<Claim> claim(Duration lease) {
    if (lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException("Invalid lease");
    var claimed = store.claim(lease);
    if (claimed.isPresent() && claimed.get().exhausted()) {
      recordTarget(claimed.get(), "ATTEMPTS_EXHAUSTED", claimed.get().previousStatus());
      return Optional.empty();
    }
    return claimed;
  }

  @Transactional
  public boolean process(Claim claim) {
    if (claim.exhausted() || !store.lockClaim(claim)) return false;
    String outcome = events.rejudge(claim.inboxId(), claim.expectedVersion(), claim.reasonCode());
    finish(claim, outcome);
    return true;
  }

  /** 업무 변경이 롤백된 뒤 별도 트랜잭션에서 실패를 남긴다. 예외 원문은 저장하지 않는다. */
  @Transactional
  public void fail(Claim claim) {
    if (store.lockClaim(claim)) finish(claim, "PROCESSING_FAILED");
  }

  private void finish(Claim c, String outcome) {
    store.finish(c, outcome);
    recordTarget(c, outcome, "RUNNING");
  }

  private void recordTarget(Claim c, String outcome, String before) {
    audit.record(
        new AuditLog.Entry(
            "SYSTEM",
            "inbox-rejudgement-worker",
            AuditLog.Action.INBOX_REJUDGEMENT_TARGET_FINISHED,
            "INBOX_REJUDGEMENT_TARGET",
            c.targetId(),
            before,
            "DONE",
            outcome,
            c.runId().toString(),
            clock.instant()));
  }

  private void record(
      String actor,
      AuditLog.Action action,
      UUID id,
      String before,
      String after,
      String reason,
      UUID runId) {
    audit.record(
        new AuditLog.Entry(
            "OPERATOR",
            actor,
            action,
            "INBOX_REJUDGEMENT",
            id,
            before,
            after,
            reason,
            runId.toString(),
            clock.instant()));
  }

  private static void page(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page");
  }

  private static void reason(String code) {
    if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,79}"))
      throw new IllegalArgumentException("Invalid reason code");
  }

  private static void filter(Filter f) {
    if (f == null
        || f.receivedBefore() == null
        || (f.eventType() != null && !TYPES.contains(f.eventType())))
      throw new IllegalArgumentException("Invalid filter");
    if (f.reasonCode() != null) reason(f.reasonCode());
  }
}
