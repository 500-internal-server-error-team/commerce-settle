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
import java.util.function.Supplier;
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

  /** 클래스 트랜잭션 안에서만 유효한 상한을 왕복 한 번으로 건다. */
  private void limits() {
    jdbc.queryForList(
        "SELECT set_config('lock_timeout','1s',true),set_config('statement_timeout','5s',true)");
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
    // 페이지의 기존 원천을 한 번에 읽고, 본문이 바뀐 행만 한 batch로 갱신한다.
    var priors = new java.util.HashMap<UUID, Event>();
    jdbc.query(
        "SELECT event_id,body::text FROM settlement.reconciliation_events WHERE run_id=? AND event_id=ANY(?::text[]::uuid[])",
        ps -> {
          ps.setObject(1, run.id());
          arrays(ps, 2, page.items(), List.of(Event::eventId));
        },
        rs -> {
          priors.put(rs.getObject(1, UUID.class), event(rs.getString(2)));
        });
    var bodies = new ArrayList<Object[]>();
    for (Event e : page.items()) {
      Event prior = priors.get(e.eventId());
      if (prior == null)
        throw new org.springframework.dao.EmptyResultDataAccessException(
            "Fixed source event missing", 1);
      if (!immutable(prior).equals(immutable(e))
          || (prior.succeededAt() != null && !prior.succeededAt().equals(e.succeededAt())))
        throw new IllegalStateException("Immutable source changed");
      String body = json(e);
      bodies.add(new Object[] {body, body, run.id(), e.eventId(), body});
    }
    jdbc.batchUpdate(
        "UPDATE settlement.reconciliation_events SET status=CASE WHEN source='PAYOUT' AND body IS DISTINCT FROM ?::jsonb THEN 'PENDING' ELSE status END,body=?::jsonb WHERE run_id=? AND event_id=? AND body IS DISTINCT FROM ?::jsonb",
        bodies);
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
    return locals(List.of(e), cutoff).get(e.eventId());
  }

  /** 검사할 이벤트 묶음의 로컬 기록을 조회 종류마다 한 문장으로 읽는다. 판정 조건은 이벤트별 조회와 같다. */
  @Override
  @Transactional(readOnly = true)
  public Map<UUID, LocalEvent> locals(List<Event> events, Instant cutoff) {
    var found = new java.util.HashMap<UUID, LocalEvent>();
    if (events.isEmpty()) return found;
    requireClosed(cutoff);
    var payouts = events.stream().filter(e -> "PAYOUT".equals(e.eventType())).toList();
    var payments = events.stream().filter(e -> !"PAYOUT".equals(e.eventType())).toList();
    if (!payouts.isEmpty()) payouts(payouts, cutoff, found);
    if (!payments.isEmpty()) payments(payments, found);
    return found;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /** 묶음 조회의 입력 배열은 text[]로 보내고 SQL에서 원래 타입으로 변환한다. */
  private static void arrays(
      java.sql.PreparedStatement ps,
      int from,
      List<Event> events,
      List<java.util.function.Function<Event, Object>> columns)
      throws java.sql.SQLException {
    for (int i = 0; i < columns.size(); i++) {
      var column = columns.get(i);
      ps.setArray(
          from + i,
          ps.getConnection()
              .createArrayOf("text", events.stream().map(e -> text(column.apply(e))).toArray()));
    }
  }

  private void payouts(List<Event> events, Instant cutoff, Map<UUID, LocalEvent> found) {
    jdbc.query(
        """
      SELECT x.event_id,p.status,p.succeeded_at,p.amount,
        CASE WHEN p.external_ref=x.event_id AND p.funding_account_ref=x.funding THEN 0 ELSE 1 END AS reference_errors
      FROM unnest(?::text[]::uuid[],?::text[],?::text[]::uuid[]) AS x(event_id,funding,business_key)
      JOIN settlement.payouts p ON p.business_key=x.business_key AND p.created_at<=?
      """,
        ps -> {
          arrays(
              ps, 1, events, List.of(Event::eventId, Event::fundingAccountRef, Event::businessKey));
          ps.setObject(4, at(cutoff));
        },
        rs -> {
          found.putIfAbsent(
              rs.getObject("event_id", UUID.class),
              new LocalEvent(
                  null,
                  false,
                  0,
                  false,
                  0,
                  false,
                  true,
                  rs.getString("status"),
                  rs.getTimestamp("succeeded_at") == null
                      ? null
                      : rs.getTimestamp("succeeded_at").toInstant(),
                  rs.getLong("amount"),
                  rs.getLong("reference_errors"),
                  Map.of()));
        });
    for (Event e : events)
      found.putIfAbsent(
          e.eventId(),
          new LocalEvent(null, false, 0, false, 0, false, false, null, null, 0, 0, Map.of()));
  }

  private record Receipt(String status, boolean quarantined, long items, long matching) {}

  private void payments(List<Event> events, Map<UUID, LocalEvent> found) {
    var receipts = new java.util.HashMap<UUID, Receipt>();
    jdbc.query(
        """
      SELECT x.event_id,
        (SELECT status FROM settlement.inbox WHERE consumer=? AND event_id=x.event_id) AS status,
        EXISTS(SELECT 1 FROM settlement.inbox_quarantines WHERE consumer=? AND source_topic=x.topic AND source_partition=x.part AND source_offset=x.off)
          OR EXISTS(SELECT 1 FROM settlement.inbox_conflicts WHERE consumer=? AND event_id=x.event_id) AS quarantined,
        c.items,c.matching
      FROM unnest(?::text[]::uuid[],?::text[]::uuid[],?::text[],?::text[]::int[],?::text[]::bigint[],?::text[]::bigint[],?::text[]::bigint[],
        ?::text[]::bigint[],?::text[]::bigint[],?::text[]::bigint[],?::text[]::bigint[],?::text[])
        AS x(event_id,payment_id,topic,part,off,external_amount,mileage_amount,platform_coupon_amount,settlement_base_amount,fee_amount,net_amount,event_type)
      CROSS JOIN LATERAL (SELECT count(*) AS items,count(*) FILTER(WHERE i.external_amount=x.external_amount AND i.mileage_amount=x.mileage_amount
         AND i.platform_coupon_amount=x.platform_coupon_amount AND i.settlement_base_amount=x.settlement_base_amount
         AND i.fee_amount=x.fee_amount AND i.net_amount=x.net_amount
         AND (x.event_type<>'PaymentConfirmed' OR EXISTS(SELECT 1 FROM settlement.inbox n WHERE n.consumer=? AND n.event_id=x.event_id
           AND n.payload->'payload'->>'sellerId'=i.seller_id::text
           AND n.payload->'payload'->>'orderId'=i.order_id::text
           AND n.payload->'payload'->>'productId'=i.product_id::text))) AS matching
        FROM settlement.settlement_items i WHERE i.payment_id=x.payment_id) c
      """,
        ps -> {
          ps.setString(1, consumer);
          ps.setString(2, consumer);
          ps.setString(3, consumer);
          arrays(
              ps,
              4,
              events,
              List.of(
                  Event::eventId,
                  Event::paymentId,
                  Event::topic,
                  Event::partition,
                  Event::offset,
                  Event::externalAmount,
                  Event::mileageAmount,
                  Event::platformCouponAmount,
                  Event::settlementBaseAmount,
                  Event::feeAmount,
                  Event::sellerNetAmount,
                  Event::eventType));
          ps.setString(16, consumer);
        },
        rs -> {
          receipts.put(
              rs.getObject("event_id", UUID.class),
              new Receipt(
                  rs.getString("status"),
                  rs.getBoolean("quarantined"),
                  rs.getLong("items"),
                  rs.getLong("matching")));
        });
    var unseen =
        events.stream()
            .filter(
                e ->
                    receipts.get(e.eventId()).status() == null
                        && !receipts.get(e.eventId()).quarantined())
            .toList();
    var correlated = quarantinedByBody(unseen);
    var legs = new java.util.HashMap<UUID, List<Map<String, Object>>>();
    jdbc.query(
        """
       SELECT x.event_id AS reconciliation_event_id,e.leg,e.amount,e.item_id,e.business_ref,e.reversal_of,EXISTS(SELECT 1 FROM settlement.settlement_items i WHERE i.id=e.item_id AND i.payment_id=e.payment_id) AS valid_item,
       CASE WHEN e.reversal_of IS NULL THEN true ELSE EXISTS(SELECT 1 FROM settlement.settlement_entries o WHERE o.id=e.reversal_of AND o.item_id=e.item_id AND o.leg=e.leg AND o.side=e.side AND o.amount=-e.amount AND o.policy_version=e.policy_version AND o.reversal_of IS NULL) END AS valid_reversal
       FROM unnest(?::text[]::uuid[],?::text[]::uuid[],?::text[]::timestamptz[]) AS x(event_id,payment_id,occurred_at)
       JOIN settlement.settlement_entries e ON e.source_event_id=x.event_id AND e.payment_id=x.payment_id AND e.source_occurred_at=x.occurred_at
       """,
        ps -> arrays(ps, 1, events, List.of(Event::eventId, Event::paymentId, Event::occurredAt)),
        rs -> {
          var row = new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs, 0);
          UUID source = (UUID) row.remove("reconciliation_event_id");
          legs.computeIfAbsent(source, k -> new ArrayList<>()).add(row);
        });
    for (Event e : events) {
      Receipt receipt = receipts.get(e.eventId());
      found.put(
          e.eventId(),
          payment(
              e,
              receipt,
              receipt.quarantined() || correlated.contains(e.eventId()),
              legs.getOrDefault(e.eventId(), List.of())));
    }
  }

  /** 수신 기록이 없는 이벤트는 같은 topic의 격리 원문에서 eventId를 찾는다. */
  private java.util.Set<UUID> quarantinedByBody(List<Event> events) {
    var correlated = new java.util.HashSet<UUID>();
    if (events.isEmpty()) return correlated;
    var candidates = new java.util.HashMap<UUID, List<byte[]>>();
    jdbc.query(
        """
      SELECT x.event_id,q.raw_body FROM unnest(?::text[]::uuid[],?::text[]) AS x(event_id,topic)
      CROSS JOIN LATERAL (SELECT raw_body FROM settlement.inbox_quarantines WHERE consumer=? AND source_topic=x.topic
        AND raw_body IS NOT NULL AND position(convert_to(x.event_id::text,'UTF8') in raw_body)>0 LIMIT 101) q
      """,
        ps -> {
          arrays(ps, 1, events, List.of(Event::eventId, Event::topic));
          ps.setString(3, consumer);
        },
        rs -> {
          candidates
              .computeIfAbsent(rs.getObject("event_id", UUID.class), k -> new ArrayList<>())
              .add(rs.getBytes("raw_body"));
        });
    for (Event e : events) {
      var raws = candidates.getOrDefault(e.eventId(), List.of());
      if (raws.size() > 100)
        throw new IllegalStateException("Quarantine correlation requires review");
      for (byte[] raw : raws) {
        if (raw.length > 1048576)
          throw new IllegalStateException("Quarantine evidence exceeds budget");
        try {
          var root = mapper.readTree(raw);
          if (root != null
              && root.hasNonNull("eventId")
              && e.eventId().toString().equals(root.get("eventId").asText()))
            correlated.add(e.eventId());
        } catch (java.io.IOException ignored) {
          /* Invalid bytes cannot prove an event identity. */
        }
      }
    }
    return correlated;
  }

  private LocalEvent payment(
      Event e, Receipt receipt, boolean quarantined, List<Map<String, Object>> legs) {
    int sign = "RefundFinancialCompleted".equals(e.eventType()) ? -1 : 1;
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
        receipt.status(),
        quarantined,
        receipt.items(),
        receipt.matching() == 1,
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
    // 전체 건수·최초 시각을 창 함수로 같은 읽기에서 구해 대상 집합을 한 번만 평가한다.
    var rows =
        jdbc.queryForList(
            "SELECT p.*,count(*) OVER() AS progress_total,min(\"createdAt\") OVER() AS progress_oldest FROM ("
                + sql
                + ") p ORDER BY id LIMIT 100",
            at(cutoff));
    long count = rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("progress_total")).longValue();
    var members =
        rows.stream()
            .map(
                row -> {
                  var member = new java.util.LinkedHashMap<>(row);
                  member.remove("progress_total");
                  member.remove("progress_oldest");
                  return view(member);
                })
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
                    : ((java.sql.Timestamp) rows.getFirst().get("progress_oldest"))
                        .toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Seoul"))
                        .toOffsetDateTime()
                        .toString())));
  }

  private void check(List<Finding> checks, String key, String sql, Object... args) {
    long count = jdbc.queryForObject(sql, Long.class, args);
    checks.add(new Finding(key, count == 0 ? "MATCH" : "DEFECT", key, count, 0, Map.of()));
  }

  /**
   * 한 호출의 판정을 한 문장으로 기록한다. 회차 행을 공유 잠금으로 확인해 만료·교체된 실행자의 기록을 막고, 분류·사유가 바뀐 이벤트만 판정 순서대로 이력에 남긴다.
   */
  @Override
  public void record(Run run, Map<UUID, Finding> findings) {
    if (findings.isEmpty()) return;
    limits();
    var ids = new ArrayList<String>();
    var statuses = new ArrayList<String>();
    var reasons = new ArrayList<String>();
    var differences = new ArrayList<String>();
    var evidence = new ArrayList<String>();
    findings.forEach(
        (id, f) -> {
          ids.add(id.toString());
          statuses.add(f.status());
          reasons.add(f.reason());
          differences.add(Long.toString(f.difference()));
          evidence.add(json(f.evidence()));
        });
    long updated =
        jdbc.query(
            """
      WITH lease AS (SELECT id FROM settlement.reconciliation_runs
        WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() FOR SHARE),
      x AS (SELECT * FROM unnest(?::text[]::uuid[],?::text[],?::text[],?::text[]::bigint[],?::text[]::jsonb[])
        WITH ORDINALITY AS x(event_id,status,reason,difference,evidence,n)),
      u AS (UPDATE settlement.reconciliation_events e SET status=x.status,reason=x.reason,difference=x.difference,
          evidence=x.evidence,checked_at=clock_timestamp()
        FROM x,lease WHERE e.run_id=lease.id AND e.event_id=x.event_id
        RETURNING x.n,e.run_id,e.event_id,e.status,e.reason,e.evidence,old.status AS old_status,old.reason AS old_reason),
      h AS (INSERT INTO settlement.reconciliation_history(run_id,event_id,status,reason,evidence)
        SELECT run_id,event_id,status,reason,evidence FROM u
        WHERE (old_status,coalesce(old_reason,'')) IS DISTINCT FROM (status,reason) ORDER BY n)
      SELECT count(*) FROM u
      """,
            ps -> {
              ps.setObject(1, run.id());
              ps.setObject(2, run.token());
              var c = ps.getConnection();
              ps.setArray(3, c.createArrayOf("text", ids.toArray()));
              ps.setArray(4, c.createArrayOf("text", statuses.toArray()));
              ps.setArray(5, c.createArrayOf("text", reasons.toArray()));
              ps.setArray(6, c.createArrayOf("text", differences.toArray()));
              ps.setArray(7, c.createArrayOf("text", evidence.toArray()));
            },
            rs -> {
              rs.next();
              return rs.getLong(1);
            });
    if (updated != findings.size()) throw new IllegalStateException("Reconciliation lease lost");
  }

  @Override
  public void finish(Run run, Supplier<List<Finding>> checks) {
    fence(run);
    // 이벤트는 임대 보유자만 쓰므로 전체 검사 전후의 건수가 같다. 세 건수를 한 번에 읽는다.
    var counts =
        jdbc.queryForMap(
            """
      SELECT count(*) FILTER(WHERE status='PENDING') AS untouched,count(*) FILTER(WHERE status<>'MATCH') AS unresolved,
        count(*) FILTER(WHERE status='DEFECT') AS defects FROM settlement.reconciliation_events WHERE run_id=?
      """,
            run.id());
    if (((Number) counts.get("untouched")).longValue() > 0) {
      release(run, "PENDING", 0);
      return;
    }
    List<Finding> local = checks.get();
    // 전체 검사가 임대 시간을 쓰므로 기록 전에 임대를 다시 확인한다.
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
    long unresolved = ((Number) counts.get("unresolved")).longValue();
    long defects = ((Number) counts.get("defects")).longValue();
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
