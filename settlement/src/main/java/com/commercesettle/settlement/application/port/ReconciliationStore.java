package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.application.port.ReconciliationSources.Event;
import com.commercesettle.settlement.application.port.ReconciliationSources.Page;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface ReconciliationStore {
  UUID request(Instant cutoff, String actor);

  Optional<Instant> latestCutoff();

  Optional<Run> claim();

  void page(Run run, Page page);

  List<Event> pending(Run run, int limit);

  SourcePage sourcePage(Run run, UUID eventId);

  void refresh(
      Run run,
      SourcePage target,
      com.commercesettle.settlement.application.port.ReconciliationSources.Page page);

  LocalEvent local(Event event, Instant cutoff);

  List<Finding> localChecks(Instant cutoff, Instant snapshot);

  void result(Run run, Event event, Finding finding);

  /** 미검사 이벤트가 남으면 다음 호출로 넘기고, 모두 검사한 회차에서만 원장 전체 검사를 실행한다. */
  void finish(Run run, Supplier<List<Finding>> checks);

  void failed(Run run, String reason);

  Map<String, Object> get(UUID id);

  Map<String, Object> results(UUID id, int page, int size);

  List<Map<String, Object>> history(UUID id);

  record SourcePage(String source, String cursor, List<UUID> eventIds) {}

  record Run(
      UUID id,
      UUID token,
      Instant cutoff,
      Instant snapshot,
      String cursor,
      String stage,
      long total,
      long captured) {}

  record LocalEvent(
      String inboxStatus,
      boolean quarantined,
      long itemCount,
      boolean amountsMatch,
      long entryCount,
      boolean entriesMatch,
      boolean exists,
      String status,
      Instant succeededAt,
      long amount,
      long referenceErrors,
      Map<String, Object> evidence) {}

  record Finding(
      String key,
      String status,
      String reason,
      long count,
      long difference,
      Map<String, Object> evidence) {
    public static Finding of(String key, String status, String reason) {
      return new Finding(key, status, reason, 1, 0, Map.of());
    }
  }
}
