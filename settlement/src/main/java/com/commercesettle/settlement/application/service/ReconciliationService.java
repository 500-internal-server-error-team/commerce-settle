package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.port.ReconciliationPositions;
import com.commercesettle.settlement.application.port.ReconciliationSources;
import com.commercesettle.settlement.application.port.ReconciliationSources.Event;
import com.commercesettle.settlement.application.port.ReconciliationSources.Page;
import com.commercesettle.settlement.application.port.ReconciliationStore;
import com.commercesettle.settlement.application.port.ReconciliationStore.Finding;
import com.commercesettle.settlement.application.port.ReconciliationStore.LocalEvent;
import com.commercesettle.settlement.application.port.ReconciliationStore.Run;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 외부 읽기 동안 트랜잭션을 잡지 않고, 고정 페이지·판정을 임대 token으로 짧게 커밋한다. */
@Service
public class ReconciliationService {
  private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);
  private final ReconciliationStore store;
  private final ReconciliationSources sources;
  private final ReconciliationPositions positions;
  private final Clock clock;

  public ReconciliationService(
      ReconciliationStore store,
      ReconciliationSources sources,
      ReconciliationPositions positions,
      Clock clock) {
    this.store = store;
    this.sources = sources;
    this.positions = positions;
    this.clock = clock;
  }

  public UUID request(Instant cutoff, String actor) {
    Instant fixed = cutoff.truncatedTo(ChronoUnit.MICROS);
    if (fixed.plus(Duration.ofMinutes(10)).isAfter(clock.instant())) {
      throw new com.commercesettle.settlement.application.exception
          .ReconciliationCutoffNotReadyException();
    }
    return store.request(fixed, actor);
  }

  /** 한 호출은 한 페이지 또는 최대 100개 미완료 키만 진행한다. 다음 호출은 같은 회차를 잇는다. */
  public boolean tick() {
    var claimed = store.claim();
    if (claimed.isEmpty()) return false;
    Run run = claimed.get();
    try {
      if (!"CHECK".equals(run.stage())) {
        Page page =
            "PAYMENT".equals(run.stage())
                ? sources.payments(run.cutoff(), run.snapshot(), run.cursor())
                : sources.payouts(run.cutoff(), run.snapshot(), run.cursor());
        store.page(run, page);
      } else {
        // Inbox APPLIED를 먼저 검사하므로 Kafka 장애가 이미 반영된 이벤트 검사를 막지 않는다.
        Map<String, Long> offsets = null;
        var refreshedPages = new java.util.HashMap<String, Map<UUID, Event>>();
        var targets = new java.util.HashMap<UUID, ReconciliationStore.SourcePage>();
        var pending = store.pending(run, 100);
        var locals = store.locals(pending, run.cutoff());
        // 판정은 묶어서 기록한다. 중간 실패 전 판정은 기존처럼 남기고 실패를 그대로 전달한다.
        var findings = new java.util.LinkedHashMap<UUID, Finding>();
        try {
          for (Event stored : pending) {
            Event event = stored;
            LocalEvent local = locals.get(event.eventId());
            if ("PAYOUT".equals(event.eventType()) || !"APPLIED".equals(local.inboxStatus())) {
              var target = targets.get(event.eventId());
              if (target == null) {
                target = store.sourcePage(run, event.eventId());
                for (UUID member : target.eventIds()) targets.put(member, target);
              }
              String key = target.source() + ":" + target.cursor();
              var refreshed = refreshedPages.get(key);
              if (refreshed == null) {
                Page page =
                    "PAYMENT".equals(target.source())
                        ? sources.payments(run.cutoff(), run.snapshot(), target.cursor())
                        : sources.payouts(run.cutoff(), run.snapshot(), target.cursor());
                store.refresh(run, target, page);
                refreshed =
                    page.items().stream()
                        .collect(
                            java.util.stream.Collectors.toMap(
                                Event::eventId, java.util.function.Function.identity()));
                refreshedPages.put(key, refreshed);
              }
              event = refreshed.get(stored.eventId());
              if (event == null)
                throw new IllegalStateException("Pending source event disappeared");
            }
            Finding result;
            if ("PAYOUT".equals(event.eventType())) result = payout(event, local, run.cutoff());
            else {
              if (local.inboxStatus() == null
                  && !local.quarantined()
                  && "PUBLISHED".equals(event.publicationState())) {
                if (offsets == null) offsets = positions.committed();
                Long committed = offsets.get(event.topic() + ":" + event.partition());
                // ack는 Inbox 커밋 뒤다. offset 조회 뒤 새 DB 읽기로 소비 중의 정상 커밋을 유실로 오인하지 않는다.
                if (committed != null && committed > event.offset())
                  local = store.local(event, run.cutoff());
              }
              result = payment(event, local, offsets == null ? Map.of() : offsets);
            }
            findings.put(event.eventId(), result);
          }
        } catch (RuntimeException failure) {
          try {
            store.record(run, findings);
          } catch (RuntimeException suppressed) {
            failure.addSuppressed(suppressed);
          }
          throw failure;
        }
        store.record(run, findings);
        store.finish(run, () -> store.localChecks(run.cutoff(), run.snapshot()));
      }
      return true;
    } catch (RuntimeException failure) {
      store.failed(run, failure.getClass().getSimpleName());
      LOG.atWarn()
          .addKeyValue("event", "settlement.reconciliation")
          .addKeyValue("jobId", run.id())
          .addKeyValue("errorCode", "RECONCILIATION_UNAVAILABLE")
          .log("Reconciliation interrupted; fixed target and checkpoint retained");
      return false;
    }
  }

  static Finding payment(Event event, LocalEvent local, Map<String, Long> offsets) {
    String key = event.eventId().toString();
    if ("APPLIED".equals(local.inboxStatus())) {
      boolean match =
          local.itemCount() == 1
              && local.amountsMatch()
              && local.entryCount() == 5
              && local.entriesMatch();
      return new Finding(
          key,
          match ? "MATCH" : "DEFECT",
          match ? "APPLIED_MATCH" : "APPLIED_AMOUNT_OR_REFERENCE_MISMATCH",
          1,
          local.amount(),
          Map.of(
              "itemCount",
              local.itemCount(),
              "entryCount",
              local.entryCount(),
              "itemAmountsMatch",
              local.amountsMatch(),
              "entryAmountsMatch",
              local.entriesMatch(),
              "local",
              local.evidence()));
    }
    if (local.quarantined() || "REVIEW_REQUIRED".equals(local.inboxStatus()))
      return Finding.of(key, "QUARANTINED", "SOURCE_REQUIRES_REVIEW");
    if (local.inboxStatus() != null)
      return Finding.of(key, "WAIT_APPLICATION", "INBOX_NOT_APPLIED");
    if (!"PUBLISHED".equals(event.publicationState()))
      return Finding.of(key, "WAIT_PUBLICATION", "PUBLICATION_POSITION_UNKNOWN");
    Long committed = offsets.get(event.topic() + ":" + event.partition());
    return Finding.of(
        key,
        committed != null && committed > event.offset() ? "DEFECT" : "WAIT_DELIVERY",
        committed != null && committed > event.offset()
            ? "RECEIPT_LOST_AFTER_COMMIT"
            : "CONSUMER_NOT_PAST_EVENT");
  }

  static Finding payout(Event event, LocalEvent local, Instant cutoff) {
    String key = event.eventId().toString();
    if ((event.succeededAt() == null || event.succeededAt().isAfter(cutoff))
        && event.debitCount() > 0)
      return Finding.of(key, "DEFECT", "DEBIT_WITHOUT_PAYOUT_SUCCESS_AT_CUTOFF");
    if (event.succeededAt() != null && event.succeededAt().isAfter(cutoff))
      return Finding.of(key, "MATCH", "PAYOUT_SUCCEEDED_AFTER_CUTOFF");
    if ("DECLINED".equals(event.status())) {
      return Finding.of(
          key,
          local.exists() && "DECLINED".equals(local.status()) ? "MATCH" : "IN_PROGRESS",
          "DECLINED_PAYOUT_RESULT");
    }
    if (event.succeededAt() == null)
      return Finding.of(key, "IN_PROGRESS", "PAYOUT_NOT_SUCCESSFUL_AT_CUTOFF");
    if (!local.exists()) return Finding.of(key, "DEFECT", "PAYOUT_WITHOUT_SETTLEMENT");
    if (local.succeededAt() == null || local.succeededAt().isAfter(cutoff))
      return Finding.of(key, "IN_PROGRESS", "PAYOUT_RESULT_NOT_APPLIED_AT_CUTOFF");
    boolean match =
        "SUCCEEDED".equals(local.status())
            && local.amount() == event.amount()
            && local.referenceErrors() == 0
            && local.succeededAt().equals(event.succeededAt())
            && event.debitCount() == 1
            && event.debitAmount() == -event.amount();
    return new Finding(
        key,
        match ? "MATCH" : "DEFECT",
        match ? "PAYOUT_DEBIT_MATCH" : "PAYOUT_DEBIT_MISMATCH",
        1,
        event.debitAmount() + local.amount(),
        Map.of("debitCount", event.debitCount(), "debitAmount", event.debitAmount()));
  }
}
