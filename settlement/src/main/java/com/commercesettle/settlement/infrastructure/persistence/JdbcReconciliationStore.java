package com.commercesettle.settlement.infrastructure.persistence;

import com.commercesettle.settlement.application.port.ReconciliationSources.Event;
import com.commercesettle.settlement.application.port.ReconciliationSources.Page;
import com.commercesettle.settlement.application.port.ReconciliationStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(timeout = 10)
public class JdbcReconciliationStore implements ReconciliationStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final String consumer;

  public JdbcReconciliationStore(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      @Value("${spring.kafka.consumer.group-id:settlement.v1}") String consumer) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.consumer = consumer;
  }

  private void limits() {
    jdbc.execute("SET LOCAL lock_timeout='1s'");
    jdbc.execute("SET LOCAL statement_timeout='5s'");
  }

  private static OffsetDateTime at(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid reconciliation data", e);
    }
  }

  private Event event(String value) {
    try {
      return mapper.readValue(value, Event.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored source", e);
    }
  }

  @Override
  public UUID request(Instant cutoff, String actor) {
    limits();
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
      INSERT INTO settlement.reconciliation_runs(id,cutoff_at,requested_by) VALUES(?,?,?)
      ON CONFLICT(cutoff_at) DO NOTHING
      """,
        id,
        at(cutoff),
        actor);
    UUID current =
        jdbc.queryForObject(
            "SELECT id FROM settlement.reconciliation_runs WHERE cutoff_at=?",
            UUID.class,
            at(cutoff));
    if (!"scheduler".equals(actor)) {
      jdbc.update(
          "UPDATE settlement.reconciliation_runs SET next_run_at=clock_timestamp() WHERE id=?",
          current);
      jdbc.update(
          "INSERT INTO settlement.reconciliation_history(run_id,status,reason,evidence) VALUES(?,'REQUESTED','OPERATOR_REQUEST',?::jsonb)",
          current,
          json(Map.of("actor", actor)));
    }
    return current;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Instant> latestCutoff() {
    var value =
        jdbc.queryForObject(
            "SELECT max(cutoff_at) FROM settlement.reconciliation_runs WHERE extract(hour from cutoff_at AT TIME ZONE 'Asia/Seoul')=10 AND extract(minute from cutoff_at AT TIME ZONE 'Asia/Seoul')=0 AND extract(second from cutoff_at AT TIME ZONE 'Asia/Seoul')=0",
            OffsetDateTime.class);
    return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
  }

  @Override
  public Optional<Run> claim() {
    limits();
    UUID token = UUID.randomUUID();
    List<Run> runs =
        jdbc.query(
            """
      UPDATE settlement.reconciliation_runs SET lease_token=?, lease_until=clock_timestamp()+interval '60 seconds',
        attempts=attempts+1,status='RUNNING',updated_at=clock_timestamp()
      WHERE id=(SELECT id FROM settlement.reconciliation_runs WHERE status<>'COMPLETED'
        AND next_run_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<clock_timestamp())
        ORDER BY next_run_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
      RETURNING id,cutoff_at,snapshot_at,cursor,stage,expected_total,captured
      """,
            (rs, n) ->
                new Run(
                    rs.getObject("id", UUID.class),
                    token,
                    rs.getTimestamp("cutoff_at").toInstant(),
                    rs.getTimestamp("snapshot_at") == null
                        ? null
                        : rs.getTimestamp("snapshot_at").toInstant(),
                    rs.getString("cursor"),
                    rs.getString("stage"),
                    rs.getObject("expected_total") == null ? -1 : rs.getLong("expected_total"),
                    rs.getLong("captured")),
            token);
    runs.forEach(
        run ->
            history(
                run,
                null,
                "RUNNING",
                "CLAIM_OR_RESUME",
                Map.of("stage", run.stage(), "executionId", run.token().toString())));
    return runs.stream().findFirst();
  }

  private void fence(Run run) {
    limits();
    int n =
        jdbc.update(
            """
      UPDATE settlement.reconciliation_runs SET lease_until=clock_timestamp()+interval '60 seconds',updated_at=clock_timestamp()
      WHERE id=? AND lease_token=? AND lease_until>clock_timestamp()
      """,
            run.id(),
            run.token());
    if (n != 1) throw new IllegalStateException("Reconciliation lease lost");
  }

  private void release(Run run, String status, int delay) {
    jdbc.update(
        """
      UPDATE settlement.reconciliation_runs SET status=?,lease_token=NULL,lease_until=NULL,
       next_run_at=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp()
      WHERE id=? AND lease_token=?
      """,
        status,
        delay,
        run.id(),
        run.token());
  }

  private void history(Run run, UUID event, String status, String reason, Object evidence) {
    jdbc.update(
        "INSERT INTO settlement.reconciliation_history(run_id,event_id,status,reason,evidence) VALUES(?,?,?,?,?::jsonb)",
        run.id(),
        event,
        status,
        reason,
        json(evidence));
  }

  @Override
  public void page(Run run, Page page) {
    fence(run);
    if (!page.cutoffAt().equals(run.cutoff())
        || page.snapshotAt() == null
        || (run.snapshot() != null && !page.snapshotAt().equals(run.snapshot()))
        || (run.total() != -1 && page.totalElements() != run.total())
        || page.items().size() > 100
        || page.totalElements() < run.captured() + page.items().size()
        || (page.nextCursor() != null
            && (page.items().isEmpty() || page.nextCursor().equals(run.cursor())))
        || (page.nextCursor() == null
            && page.totalElements() != run.captured() + page.items().size()))
      throw new IllegalStateException("Source page does not match fixed baseline");
    if (page.items().stream().map(Event::eventId).distinct().count() != page.items().size())
      throw new IllegalStateException("Duplicate source event in page");
    for (Event e : page.items()) {
      if (e.createdAt().isAfter(page.snapshotAt()) || e.occurredAt().isAfter(run.cutoff()))
        throw new IllegalStateException("Source event outside fixed baseline");
      String body = json(e);
      // 금융 원값은 불변이다. 발행 위치와 최초 성공/차감 관측만 후속 읽기로 갱신한다.
      List<String> old =
          jdbc.queryForList(
              "SELECT body::text FROM settlement.reconciliation_events WHERE run_id=? AND event_id=?",
              String.class,
              run.id(),
              e.eventId());
      if (!old.isEmpty()) {
        Event prior = event(old.getFirst());
        if (!immutable(prior).equals(immutable(e))
            || (prior.succeededAt() != null && !prior.succeededAt().equals(e.succeededAt())))
          throw new IllegalStateException("Immutable financial source changed");
        jdbc.update(
            "UPDATE settlement.reconciliation_events SET status=CASE WHEN source='PAYOUT' AND body IS DISTINCT FROM ?::jsonb THEN 'PENDING' ELSE status END,body=?::jsonb WHERE run_id=? AND event_id=?",
            body,
            body,
            run.id(),
            e.eventId());
      } else
        jdbc.update(
            "INSERT INTO settlement.reconciliation_events(run_id,event_id,source,source_cursor,body) VALUES(?,?,?,?,?::jsonb)",
            run.id(),
            e.eventId(),
            run.stage(),
            run.cursor(),
            body);
    }
    boolean end = page.nextCursor() == null;
    if (end
        && jdbc.queryForObject(
                "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=? AND source=?",
                Long.class,
                run.id(),
                run.stage())
            != page.totalElements())
      throw new IllegalStateException("Source membership changed or duplicated");
    String stage = end ? ("PAYMENT".equals(run.stage()) ? "PAYOUT" : "CHECK") : run.stage();
    jdbc.update(
        """
      UPDATE settlement.reconciliation_runs SET snapshot_at=?,cursor=?,stage=?,expected_total=?,captured=? WHERE id=? AND lease_token=?
      """,
        at(page.snapshotAt()),
        end ? null : page.nextCursor(),
        stage,
        end ? null : page.totalElements(),
        end ? 0 : run.captured() + page.items().size(),
        run.id(),
        run.token());
    history(
        run,
        null,
        "CHECKPOINT",
        "PAGE_COMMITTED",
        Map.of("stage", run.stage(), "rows", page.items().size(), "total", page.totalElements()));
    release(run, "PENDING", 0);
  }

  private List<Object> immutable(Event e) {
    return java.util.Arrays.asList(
        e.eventId(),
        e.eventType(),
        e.occurredAt(),
        e.createdAt(),
        e.paymentId(),
        e.refundId(),
        e.externalAmount(),
        e.mileageAmount(),
        e.platformCouponAmount(),
        e.settlementBaseAmount(),
        e.feeAmount(),
        e.sellerNetAmount(),
        e.topic(),
        e.businessKey(),
        e.amount(),
        e.fundingAccountRef());
  }

  @Override
  @Transactional(readOnly = true)
  public List<Event> pending(Run run, int limit) {
    return jdbc.query(
        """
      SELECT body::text FROM settlement.reconciliation_events WHERE run_id=? AND status<>'MATCH'
      AND (checked_at IS NULL OR checked_at<(SELECT updated_at FROM settlement.reconciliation_runs WHERE id=?))
      ORDER BY checked_at NULLS FIRST,event_id LIMIT ?
      """,
        (rs, n) -> event(rs.getString(1)),
        run.id(),
        run.id(),
        limit);
  }

  @Override
  @Transactional(readOnly = true)
  public SourcePage sourcePage(Run run, UUID eventId) {
    var row =
        jdbc.queryForMap(
            "SELECT source,source_cursor FROM settlement.reconciliation_events WHERE run_id=? AND event_id=?",
            run.id(),
            eventId);
    var ids =
        jdbc.queryForList(
            "SELECT event_id FROM settlement.reconciliation_events WHERE run_id=? AND source=? AND source_cursor IS NOT DISTINCT FROM ?::text ORDER BY event_id",
            UUID.class,
            run.id(),
            row.get("source"),
            row.get("source_cursor"));
    return new SourcePage(row.get("source").toString(), (String) row.get("source_cursor"), ids);
  }

  @Override
  public void refresh(Run run, SourcePage target, Page page) {
    fence(run);
    if (!run.cutoff().equals(page.cutoffAt())
        || !run.snapshot().equals(page.snapshotAt())
        || !new java.util.HashSet<>(target.eventIds())
            .equals(new java.util.HashSet<>(page.items().stream().map(Event::eventId).toList()))
        || page.items().stream().map(Event::eventId).distinct().count() != page.items().size())
      throw new IllegalStateException("Fixed source page changed");
    if (page.totalElements()
        != jdbc.queryForObject(
            "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=? AND source=?",
            Long.class,
            run.id(),
            target.source())) throw new IllegalStateException("Source membership changed");
    for (Event e : page.items()) {
      Event prior =
          event(
              jdbc.queryForObject(
                  "SELECT body::text FROM settlement.reconciliation_events WHERE run_id=? AND event_id=?",
                  String.class,
                  run.id(),
                  e.eventId()));
      if (!immutable(prior).equals(immutable(e))
          || (prior.succeededAt() != null && !prior.succeededAt().equals(e.succeededAt())))
        throw new IllegalStateException("Immutable source changed");
      jdbc.update(
          "UPDATE settlement.reconciliation_events SET status=CASE WHEN source='PAYOUT' AND body IS DISTINCT FROM ?::jsonb THEN 'PENDING' ELSE status END,body=?::jsonb WHERE run_id=? AND event_id=?",
          json(e),
          json(e),
          run.id(),
          e.eventId());
    }
  }

  private void requireClosed(Instant cutoff) {
    Boolean open =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND xact_start<=? + interval '1 microsecond' AND state<>'idle')",
            Boolean.class,
            at(cutoff));
    if (!Boolean.FALSE.equals(open))
      throw new org.springframework.dao.TransientDataAccessResourceException(
          "Settlement cutoff has open transactions");
  }

  @Override
  @Transactional(readOnly = true)
  public LocalEvent local(Event e, Instant cutoff) {
    requireClosed(cutoff);
    if ("PAYOUT".equals(e.eventType())) {
      var rows =
          jdbc.query(
              "SELECT status,succeeded_at,amount,CASE WHEN external_ref=? AND funding_account_ref=? THEN 0 ELSE 1 END AS reference_errors FROM settlement.payouts WHERE business_key=? AND created_at<=?",
              (rs, n) ->
                  new LocalEvent(
                      null,
                      false,
                      0,
                      false,
                      0,
                      false,
                      true,
                      rs.getString(1),
                      rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                      rs.getLong(3),
                      rs.getLong(4),
                      Map.of()),
              e.eventId(),
              e.fundingAccountRef(),
              e.businessKey(),
              at(cutoff));
      return rows.isEmpty()
          ? new LocalEvent(null, false, 0, false, 0, false, false, null, null, 0, 0, Map.of())
          : rows.getFirst();
    }
    List<String> inbox =
        jdbc.queryForList(
            "SELECT status FROM settlement.inbox WHERE consumer=? AND event_id=?",
            String.class,
            consumer,
            e.eventId());
    boolean quarantined =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
      SELECT EXISTS(SELECT 1 FROM settlement.inbox_quarantines WHERE consumer=? AND source_topic=? AND source_partition=? AND source_offset=?)
        OR EXISTS(SELECT 1 FROM settlement.inbox_conflicts WHERE consumer=? AND event_id=?)
      """,
                Boolean.class,
                consumer,
                e.topic(),
                e.partition(),
                e.offset(),
                consumer,
                e.eventId()));
    if (inbox.isEmpty() && !quarantined) {
      var candidates =
          jdbc.queryForList(
              "SELECT raw_body FROM settlement.inbox_quarantines WHERE consumer=? AND source_topic=? AND raw_body IS NOT NULL AND position(convert_to(?::text,'UTF8') in raw_body)>0 LIMIT 101",
              byte[].class,
              consumer,
              e.topic(),
              e.eventId().toString());
      if (candidates.size() > 100)
        throw new IllegalStateException("Quarantine correlation requires review");
      for (byte[] raw : candidates) {
        if (raw.length > 1048576)
          throw new IllegalStateException("Quarantine evidence exceeds budget");
        try {
          var root = mapper.readTree(raw);
          if (root != null
              && root.hasNonNull("eventId")
              && e.eventId().toString().equals(root.get("eventId").asText())) quarantined = true;
        } catch (java.io.IOException ignored) {
          /* Invalid bytes cannot prove an event identity. */
        }
      }
    }
    long items =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.settlement_items WHERE payment_id=?",
            Long.class,
            e.paymentId());
    long matching =
        jdbc.queryForObject(
            """
      SELECT count(*) FROM settlement.settlement_items WHERE payment_id=? AND external_amount=? AND mileage_amount=?
       AND platform_coupon_amount=? AND settlement_base_amount=? AND fee_amount=? AND net_amount=?
       AND (?<>'PaymentConfirmed' OR EXISTS(SELECT 1 FROM settlement.inbox n WHERE n.consumer=? AND n.event_id=?
         AND n.payload->'payload'->>'sellerId'=settlement_items.seller_id::text
         AND n.payload->'payload'->>'orderId'=settlement_items.order_id::text
         AND n.payload->'payload'->>'productId'=settlement_items.product_id::text))
      """,
            Long.class,
            e.paymentId(),
            e.externalAmount(),
            e.mileageAmount(),
            e.platformCouponAmount(),
            e.settlementBaseAmount(),
            e.feeAmount(),
            e.sellerNetAmount(),
            e.eventType(),
            consumer,
            e.eventId());
    int sign = "RefundFinancialCompleted".equals(e.eventType()) ? -1 : 1;
    var legs =
        jdbc.queryForList(
            """
       SELECT e.leg,e.amount,e.item_id,e.business_ref,e.reversal_of,EXISTS(SELECT 1 FROM settlement.settlement_items i WHERE i.id=e.item_id AND i.payment_id=e.payment_id) AS valid_item,
       CASE WHEN e.reversal_of IS NULL THEN true ELSE EXISTS(SELECT 1 FROM settlement.settlement_entries o WHERE o.id=e.reversal_of AND o.item_id=e.item_id AND o.leg=e.leg AND o.side=e.side AND o.amount=-e.amount AND o.policy_version=e.policy_version AND o.reversal_of IS NULL) END AS valid_reversal
       FROM settlement.settlement_entries e WHERE e.source_event_id=? AND e.payment_id=? AND e.source_occurred_at=?
       """,
            e.eventId(),
            e.paymentId(),
            at(e.occurredAt()));
    Map<String, Long> expected =
        Map.of(
            "EXTERNAL",
            sign * e.externalAmount(),
            "MILEAGE",
            sign * e.mileageAmount(),
            "PLATFORM_COUPON",
            sign * e.platformCouponAmount(),
            "SELLER",
            sign * e.sellerNetAmount(),
            "FEE",
            sign * e.feeAmount());
    boolean match =
        legs.size() == 5
            && legs.stream().map(x -> x.get("leg")).distinct().count() == 5
            && legs.stream()
                .allMatch(
                    x ->
                        expected.containsKey(x.get("leg"))
                            && expected.get(x.get("leg")).equals(x.get("amount"))
                            && (sign < 0) == (x.get("reversal_of") != null)
                            && Boolean.TRUE.equals(x.get("valid_reversal"))
                            && java.util.Objects.equals(
                                x.get("business_ref"), sign < 0 ? e.refundId() : e.paymentId())
                            && Boolean.TRUE.equals(x.get("valid_item")));
    long difference =
        legs.stream()
            .map(x -> java.math.BigInteger.valueOf(((Number) x.get("amount")).longValue()))
            .reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add)
            .subtract(
                java.math.BigInteger.valueOf(e.settlementBaseAmount())
                    .multiply(java.math.BigInteger.valueOf(2L * sign)))
            .longValueExact();
    return new LocalEvent(
        inbox.isEmpty() ? null : inbox.getFirst(),
        quarantined,
        items,
        matching == 1,
        legs.size(),
        match,
        false,
        null,
        null,
        difference,
        0,
        Map.of("actualLegs", legs));
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public List<Finding> localChecks(Instant cutoff, Instant snapshot) {
    requireClosed(cutoff);
    List<Finding> checks = new ArrayList<>();
    check(
        checks,
        "ENTRY_ITEM_REFERENCE",
        """
      SELECT count(*) FROM settlement.settlement_entries e LEFT JOIN settlement.settlement_items i ON i.id=e.item_id
      WHERE e.source_occurred_at<=? AND (i.id IS NULL OR i.payment_id<>e.payment_id)
      """,
        at(cutoff));
    check(
        checks,
        "REVERSAL_REFERENCE",
        """
      SELECT count(*) FROM settlement.settlement_entries e LEFT JOIN settlement.settlement_entries o ON o.id=e.reversal_of
      WHERE e.source_occurred_at<=? AND e.reversal_of IS NOT NULL
      AND (o.id IS NULL OR o.item_id<>e.item_id OR o.leg<>e.leg OR o.amount<>-e.amount OR o.reversal_of IS NOT NULL)
      """,
        at(cutoff));
    check(
        checks,
        "PAYOUT_BATCH_REFERENCE",
        """
      SELECT count(*) FROM settlement.payouts p LEFT JOIN settlement.settlement_batches b ON b.id=p.batch_id
      WHERE p.created_at<=? AND (b.id IS NULL OR b.seller_id<>p.seller_id OR b.net_amount<>p.amount)
      """,
        at(cutoff));
    check(
        checks,
        "ITEM_PAYOUT_REFERENCE",
        """
      SELECT count(*) FROM settlement.settlement_items i LEFT JOIN settlement.payouts p ON p.id=i.payout_id
      WHERE i.payout_id IS NOT NULL AND (i.created_at<=? OR EXISTS(SELECT 1 FROM settlement.settlement_entries e
       WHERE e.item_id=i.id AND e.source_occurred_at<=?)) AND (p.id IS NULL OR p.seller_id<>i.seller_id)
      """,
        at(cutoff),
        at(cutoff));
    check(
        checks,
        "RESERVATION_REFERENCE",
        """
      SELECT count(*) FROM settlement.settlement_reservations r LEFT JOIN settlement.settlement_items i ON i.id=r.item_id
      LEFT JOIN settlement.payouts p ON p.id=r.payout_id WHERE r.created_at<=?
      AND (i.id IS NULL OR r.amount<>i.net_amount OR (r.payout_id IS NOT NULL AND (p.id IS NULL OR p.seller_id<>i.seller_id OR r.payout_id IS DISTINCT FROM i.payout_id)))
      """,
        at(cutoff));
    check(
        checks,
        "PAYOUT_JOB_REFERENCE",
        """
      SELECT count(*) FROM settlement.payout_jobs j LEFT JOIN settlement.payouts p ON p.id=j.payout_id
      WHERE j.created_at<=? AND (p.id IS NULL OR p.business_key<>j.business_key)
      """,
        at(cutoff));
    check(
        checks,
        "PAYOUT_ALLOCATION",
        """
      SELECT count(*) FROM settlement.payouts p WHERE p.created_at<=? AND (p.amount IS DISTINCT FROM
       (SELECT sum(r.amount) FROM settlement.settlement_reservations r WHERE r.payout_id=p.id AND r.kind='PAYOUT')
       OR p.amount IS DISTINCT FROM (SELECT sum(i.net_amount) FROM settlement.settlement_items i WHERE i.payout_id=p.id))
      """,
        at(cutoff));
    check(
        checks,
        "ORPHAN_FINANCIAL_SOURCE",
        """
      SELECT count(*) FROM settlement.settlement_entries e WHERE e.source_occurred_at<=?
      AND NOT EXISTS(SELECT 1 FROM settlement.reconciliation_events s JOIN settlement.reconciliation_runs r ON r.id=s.run_id
       WHERE r.cutoff_at=? AND s.event_id=e.source_event_id AND s.source='PAYMENT')
      """,
        at(cutoff),
        at(cutoff));
    check(
        checks,
        "MISSING_PAYOUT_SOURCE",
        """
      SELECT count(*) FROM settlement.payouts p WHERE p.created_at<=? AND p.succeeded_at<=?
      AND NOT EXISTS(SELECT 1 FROM settlement.reconciliation_events s JOIN settlement.reconciliation_runs r ON r.id=s.run_id
       WHERE r.cutoff_at=? AND s.source='PAYOUT' AND s.body->>'businessKey'=p.business_key::text)
      """,
        at(cutoff),
        at(cutoff),
        at(cutoff));
    check(
        checks,
        "ITEM_WITHOUT_SOURCE",
        """
      SELECT count(*) FROM settlement.settlement_items i WHERE i.created_at<=?
      AND (NOT EXISTS(SELECT 1 FROM settlement.settlement_entries e WHERE e.item_id=i.id AND e.reversal_of IS NULL)
       OR NOT EXISTS(SELECT 1 FROM settlement.reconciliation_events e JOIN settlement.reconciliation_runs r ON r.id=e.run_id
        WHERE r.cutoff_at=? AND e.source='PAYMENT' AND e.body->>'eventType'='PaymentConfirmed' AND e.body->>'paymentId'=i.payment_id::text))
      """,
        at(cutoff),
        at(cutoff));
    check(
        checks,
        "DUPLICATE_PAYOUT_ASSIGNMENT",
        "SELECT count(*) FROM (SELECT item_id FROM settlement.settlement_reservations WHERE kind='PAYOUT' AND created_at<=? GROUP BY item_id HAVING count(*)>1) duplicates",
        at(cutoff));
    check(
        checks,
        "DUPLICATE_REFUND_REFERENCE",
        "SELECT count(*) FROM (SELECT business_ref FROM settlement.settlement_reservations WHERE kind='REFUND' AND created_at<=? GROUP BY business_ref HAVING count(*)>1) duplicates",
        at(cutoff));
    progress(
        checks,
        "PAYOUT_IN_PROGRESS",
        "SELECT id,status,created_at AS \"createdAt\" FROM settlement.payouts WHERE created_at<=? AND status<>'SUCCEEDED'",
        cutoff);
    progress(
        checks,
        "REFUND_RESERVATION_IN_PROGRESS",
        "SELECT id,status,created_at AS \"createdAt\" FROM settlement.settlement_reservations WHERE created_at<=? AND kind='REFUND' AND status='ACTIVE'",
        cutoff);
    return checks;
  }

  private void progress(List<Finding> checks, String key, String sql, Instant cutoff) {
    long count =
        jdbc.queryForObject("SELECT count(*) FROM (" + sql + ") progress", Long.class, at(cutoff));
    var members =
        jdbc.queryForList(sql + " ORDER BY id LIMIT 100", at(cutoff)).stream()
            .map(this::view)
            .toList();
    checks.add(
        new Finding(
            key,
            count == 0 ? "MATCH" : "IN_PROGRESS",
            key,
            count,
            0,
            Map.of(
                "members",
                members,
                "truncated",
                count > 100,
                "oldestCreatedAt",
                count == 0
                    ? ""
                    : jdbc.queryForObject(
                            "SELECT min(\"createdAt\") FROM (" + sql + ") progress",
                            OffsetDateTime.class,
                            at(cutoff))
                        .atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"))
                        .toOffsetDateTime()
                        .toString())));
  }

  private void check(List<Finding> checks, String key, String sql, Object... args) {
    long count = jdbc.queryForObject(sql, Long.class, args);
    checks.add(new Finding(key, count == 0 ? "MATCH" : "DEFECT", key, count, 0, Map.of()));
  }

  @Override
  public void result(Run run, Event event, Finding f) {
    fence(run);
    List<String> previous =
        jdbc.queryForList(
            "SELECT status||':'||coalesce(reason,'') FROM settlement.reconciliation_events WHERE run_id=? AND event_id=?",
            String.class,
            run.id(),
            event.eventId());
    jdbc.update(
        "UPDATE settlement.reconciliation_events SET status=?,reason=?,difference=?,evidence=?::jsonb,checked_at=clock_timestamp() WHERE run_id=? AND event_id=?",
        f.status(),
        f.reason(),
        f.difference(),
        json(f.evidence()),
        run.id(),
        event.eventId());
    if (previous.isEmpty() || !previous.getFirst().equals(f.status() + ":" + f.reason()))
      history(run, event.eventId(), f.status(), f.reason(), f.evidence());
  }

  @Override
  public void finish(Run run, List<Finding> local) {
    fence(run);
    for (Finding f : local)
      jdbc.update(
          """
      INSERT INTO settlement.reconciliation_checks VALUES(?,?,?,?,?,?,?::jsonb)
      ON CONFLICT(run_id,check_key) DO UPDATE SET status=excluded.status,reason=excluded.reason,count=excluded.count,difference=excluded.difference,evidence=excluded.evidence
      """,
          run.id(),
          f.key(),
          f.status(),
          f.reason(),
          f.count(),
          f.difference(),
          json(f.evidence()));
    long untouched =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=? AND status='PENDING'",
            Long.class,
            run.id());
    if (untouched > 0) {
      release(run, "PENDING", 0);
      return;
    }
    long unresolved =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=? AND status<>'MATCH'",
            Long.class,
            run.id());
    long defects =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=? AND status='DEFECT'",
            Long.class,
            run.id());
    boolean localDefect = local.stream().anyMatch(f -> "DEFECT".equals(f.status()));
    boolean localPending = local.stream().anyMatch(f -> !"MATCH".equals(f.status()));
    String status =
        unresolved == 0 && !localPending
            ? "COMPLETED"
            : defects > 0 || localDefect ? "REVIEW_REQUIRED" : "WAITING";
    jdbc.update(
        "UPDATE settlement.reconciliation_runs SET stage=?,cursor=NULL,expected_total=NULL,captured=0,completed_at=CASE WHEN ?='COMPLETED' THEN clock_timestamp() ELSE NULL END,last_error=NULL WHERE id=? AND lease_token=?",
        "CHECK",
        status,
        run.id(),
        run.token());
    history(
        run, null, status, "ROUND_EVALUATED", Map.of("unresolved", unresolved, "defects", defects));
    release(run, status, 60);
  }

  @Override
  public void failed(Run run, String reason) {
    fence(run);
    history(run, null, "UNVERIFIED", reason, Map.of());
    jdbc.update(
        "UPDATE settlement.reconciliation_runs SET last_error=? WHERE id=? AND lease_token=?",
        reason,
        run.id(),
        run.token());
    release(run, "UNVERIFIED", 60);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, Object> get(UUID id) {
    return view(
        jdbc.queryForMap(
            "SELECT id,cutoff_at,snapshot_at,stage,status,cursor,expected_total,captured,attempts,last_error,created_at,updated_at,completed_at FROM settlement.reconciliation_runs WHERE id=?",
            id));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, Object> results(UUID id, int page, int size) {
    get(id);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT event_id,body,status,reason,difference,evidence,first_seen_at,checked_at FROM settlement.reconciliation_events WHERE run_id=? ORDER BY event_id LIMIT ? OFFSET ?",
            id,
            size,
            (long) page * size);
    long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM settlement.reconciliation_events WHERE run_id=?", Long.class, id);
    return Map.of(
        "items",
        rows.stream().map(this::view).toList(),
        "page",
        page,
        "size",
        size,
        "totalElements",
        total,
        "totalPages",
        (total + size - 1) / size,
        "checks",
        jdbc
            .queryForList(
                "SELECT check_key,status,reason,count,difference,evidence FROM settlement.reconciliation_checks WHERE run_id=? ORDER BY check_key",
                id)
            .stream()
            .map(this::view)
            .toList());
  }

  @Override
  @Transactional(readOnly = true)
  public List<Map<String, Object>> history(UUID id) {
    get(id);
    return jdbc
        .queryForList(
            "SELECT id,event_id,status,reason,evidence,created_at FROM settlement.reconciliation_history WHERE run_id=? ORDER BY id DESC LIMIT 100",
            id)
        .stream()
        .map(this::view)
        .toList();
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var result = new java.util.LinkedHashMap<String, Object>();
    row.forEach(
        (key, value) -> {
          StringBuilder name = new StringBuilder();
          boolean upper = false;
          for (char c : key.toCharArray()) {
            if (c == '_') {
              upper = true;
            } else {
              name.append(upper ? Character.toUpperCase(c) : c);
              upper = false;
            }
          }
          Object converted = value;
          if (value instanceof java.sql.Timestamp t)
            converted = t.toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
          if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
            try {
              var node = mapper.readTree(value.toString());
              if ("body".equals(key)
                  && node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                for (String field :
                    List.of("occurredAt", "createdAt", "publishedAt", "succeededAt"))
                  if (object.hasNonNull(field))
                    object.put(
                        field,
                        OffsetDateTime.parse(object.get(field).asText())
                            .atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"))
                            .toOffsetDateTime()
                            .toString());
              }
              converted = node;
            } catch (JsonProcessingException e) {
              throw new IllegalStateException("Invalid stored evidence", e);
            }
          }
          result.put(name.toString(), converted);
        });
    return result;
  }
}
