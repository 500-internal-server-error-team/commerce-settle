package com.commercesettle.settlement.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.observability.infrastructure.BusinessMetrics;
import com.commercesettle.settlement.application.event.EventEnvelope;
import com.commercesettle.settlement.application.event.PaymentConfirmedPayload;
import com.commercesettle.settlement.application.port.InboxStore.Source;
import com.commercesettle.settlement.application.service.RefundEventService;
import com.commercesettle.settlement.application.service.SettlementEventService;
import com.commercesettle.settlement.infrastructure.config.BusinessMetricsConfiguration;
import com.commercesettle.settlement.infrastructure.messaging.SettlementEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BusinessMetricsIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private final DriverManagerDataSource source =
      new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  private final JdbcTemplate jdbc = new JdbcTemplate(source);

  @BeforeEach
  void clear() {
    jdbc.execute(
        "TRUNCATE settlement.settlement_items,settlement.settlement_entries,settlement.inbox,settlement.payouts,settlement.reconciliation_events,settlement.reconciliation_runs");
  }

  @Test
  void countsOneCanonicalInitialFactAfterCommitDespiteDuplicateInboxAndMultipleLedgerLegs() {
    try (var metrics = metrics()) {
      new TransactionTemplate(new DataSourceTransactionManager(source))
          .executeWithoutResult(
              tx -> {
                fact(12);
                metrics.refresh();
                assertThat(value(metrics, "completions", "order.settlement.applied")).isZero();
              });
      metrics.refresh();
      metrics.refresh();
      assertThat(value(metrics, "completions", "order.settlement.applied")).isEqualTo(1);
      assertThat(value(metrics, "duration_seconds_count", "order.settlement.applied")).isEqualTo(1);
      assertThat(value(metrics, "duration_seconds_sum", "order.settlement.applied")).isEqualTo(12);
      assertThat(value(metrics, "duration_seconds_sum", "payment.settlement.applied")).isEqualTo(5);
    }
    try (var restarted = metrics()) {
      restarted.refresh();
      assertThat(value(restarted, "completions", "order.settlement.applied")).isEqualTo(1);
    }
  }

  @Test
  void factsSelectTheSameOriginsAsTheLegacyPerLegJoin() {
    fact(12);
    fact(30);
    fact(-5);
    // 최초 leg가 없는 항목, 원 Inbox가 운영 확인으로 남은 항목, 집계 구간 밖 항목.
    UUID bare = fact(7);
    jdbc.update("DELETE FROM settlement.settlement_entries WHERE item_id=?", bare);
    UUID reviewed = fact(9);
    jdbc.update(
        "UPDATE settlement.inbox SET status='REVIEW_REQUIRED' WHERE result_ref=? AND event_id IN "
            + "(SELECT source_event_id FROM settlement.settlement_entries WHERE item_id=?)",
        reviewed,
        reviewed);
    UUID old = fact(3);
    jdbc.update(
        "UPDATE settlement.settlement_items SET created_at=created_at-interval '2000 seconds'"
            + " WHERE id=?",
        old);
    // 환불 상쇄 leg는 다른 이벤트에서 오며 원 이벤트 판정에 쓰지 않는다.
    UUID refunded = fact(20);
    jdbc.update(
        """
        INSERT INTO settlement.settlement_entries (id,item_id,payment_id,side,leg,amount,business_ref,
          source_event_id,source_occurred_at,reversal_of,policy_version,created_at)
        SELECT gen_random_uuid(),item_id,payment_id,side,leg,-amount,gen_random_uuid(),
          gen_random_uuid(),source_occurred_at,id,policy_version,created_at-interval '1 second'
        FROM settlement.settlement_entries WHERE item_id=?
        """,
        refunded);
    // 다른 consumer가 같은 이벤트를 더 먼저 기록했으면 먼저 기록한 행을 고른다.
    UUID shared = fact(15);
    jdbc.update(
        """
        INSERT INTO settlement.inbox (id,consumer,event_id,event_type,event_version,occurred_at,
          payload,raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,
          aggregate_version,status,result_ref,order_accepted_at,created_at,updated_at)
        SELECT gen_random_uuid(),'settlement.other',event_id,event_type,event_version,occurred_at,
          payload,raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,
          aggregate_version,status,result_ref,order_accepted_at-interval '40 seconds',
          created_at-interval '1 second',updated_at
        FROM settlement.inbox WHERE result_ref=? AND event_id IN
          (SELECT source_event_id FROM settlement.settlement_entries WHERE item_id=?)
        """,
        shared,
        shared);
    String legacy =
        """
        SELECT DISTINCT ON (s.id) s.created_at AS completed_at, i.order_accepted_at,
            (i.payload->'payload'->>'confirmedAt')::timestamptz AS confirmed_at
        FROM settlement.settlement_items s
        LEFT JOIN settlement.settlement_entries e ON e.item_id=s.id AND e.reversal_of IS NULL
        LEFT JOIN settlement.inbox i ON i.event_id=e.source_event_id
          AND i.result_ref=s.id AND i.event_type='PaymentConfirmed' AND i.status='APPLIED'
        WHERE s.created_at >= statement_timestamp() - interval '900 seconds'
          AND s.created_at < statement_timestamp()
        ORDER BY s.id, i.created_at NULLS LAST, e.created_at, e.id, i.id
        """;
    var expected =
        java.util.Map.of(
            "order.settlement.applied",
            "SELECT completed_at, order_accepted_at AS started_at FROM (" + legacy + ") facts",
            "payment.settlement.applied",
            "SELECT completed_at, confirmed_at AS started_at FROM (" + legacy + ") facts");
    var sources = BusinessMetricsConfiguration.sources();
    assertThat(sources.keySet()).containsAll(expected.keySet());
    for (var operation : expected.keySet()) {
      var before = rows(expected.get(operation));
      assertThat(before).hasSize(7).anyMatch(row -> row.endsWith("|null"));
      assertThat(before.stream().filter(row -> !row.endsWith("|null"))).hasSizeGreaterThan(3);
      assertThat(rows(sources.get(operation))).as(operation).isEqualTo(before);
    }
  }

  private java.util.List<String> rows(String source) {
    return jdbc.query(
        "SELECT completed_at::text || '|' || coalesce(started_at::text,'null') FROM ("
            + source
            + ") facts ORDER BY 1",
        (rs, row) -> rs.getString(1));
  }

  @Test
  void missingOrReversedOriginKeepsCompletionCountButExcludesLatency() {
    fact(12);
    fact(-5);
    jdbc.update(
        "UPDATE settlement.inbox SET order_accepted_at=NULL WHERE "
            + "order_accepted_at<statement_timestamp() AND event_id IN "
            + "(SELECT source_event_id FROM settlement.settlement_entries)");
    try (var metrics = metrics()) {
      metrics.refresh();
      assertThat(value(metrics, "completions", "order.settlement.applied")).isEqualTo(2);
      assertThat(value(metrics, "duration_seconds_count", "order.settlement.applied")).isZero();
      assertThat(value(metrics, "duration_missing", "order.settlement.applied")).isEqualTo(2);
      assertThat(value(metrics, "duration_seconds_count", "payment.settlement.applied"))
          .isEqualTo(2);
    }
  }

  @Test
  void listenerTreatsMissingMalformedAndDuplicateObservationHeadersAsMissingNotBusinessFailures()
      throws Exception {
    var mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    UUID payment = UUID.randomUUID(), order = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var payload =
        new PaymentConfirmedPayload(
            1000,
            0,
            1000,
            0,
            1000,
            payment,
            order,
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            UUID.randomUUID(),
            UUID.randomUUID(),
            150,
            850,
            now,
            "v1",
            0,
            0,
            1000);
    var event =
        new EventEnvelope<>(UUID.randomUUID(), "PaymentConfirmed", 1, now, payment, 0, payload);
    byte[] body = mapper.writeValueAsBytes(event);
    String accepted = "2026-11-12T00:00:00Z";
    String[][] cases = {
      {}, {"bad"}, {"+999999999-01-01T00:00:00Z"}, {accepted, accepted}, {accepted}
    };
    for (String[] headers : cases) {
      var service = Mockito.mock(SettlementEventService.class);
      var refunds = Mockito.mock(RefundEventService.class);
      var listener = new SettlementEventListener(mapper, service, refunds, null, "settlement.v1");
      var record =
          new ConsumerRecord<String, byte[]>(
              "commerce-settle.payment.v1", 0, 1, payment.toString(), body);
      for (String header : headers)
        record
            .headers()
            .add(
                BusinessMetrics.ORDER_ACCEPTED_AT_HEADER,
                header.getBytes(StandardCharsets.US_ASCII));
      var ack = Mockito.mock(Acknowledgment.class);
      listener.receive(record, ack);
      var argument = ArgumentCaptor.forClass(Source.class);
      Mockito.verify(service).receivePaymentConfirmed(ArgumentMatchers.any(), argument.capture());
      Mockito.verify(ack).acknowledge();
      assertThat(argument.getValue().body()).containsExactly(body);
      assertThat(argument.getValue().orderAcceptedAt())
          .isEqualTo(
              headers.length == 1 && headers[0].equals(accepted) ? Instant.parse(accepted) : null);
    }
  }

  @Test
  void legacyOrRetainedFactWithoutInboxStillCountsCompletionWithMissingDuration() {
    fact(12);
    jdbc.execute("DELETE FROM settlement.inbox");
    try (var metrics = metrics()) {
      metrics.refresh();
      assertThat(value(metrics, "completions", "order.settlement.applied")).isEqualTo(1);
      assertThat(value(metrics, "duration_seconds_count", "order.settlement.applied")).isZero();
      assertThat(value(metrics, "duration_missing", "order.settlement.applied")).isEqualTo(1);
    }
  }

  private UUID fact(int duration) {
    Instant at =
        jdbc.queryForObject("SELECT statement_timestamp()", Timestamp.class)
            .toInstant()
            .minusSeconds(3);
    UUID item = UUID.randomUUID(), payment = UUID.randomUUID(), event = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO settlement.settlement_items (id,payment_id,order_id,product_id,seller_id,
          total_amount,platform_coupon_amount,settlement_base_amount,external_amount,mileage_amount,
          fee_amount,net_amount,status,order_version,created_at,updated_at,version)
        VALUES (?,?,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),10000,0,10000,10000,0,
          1500,8500,'WAITING_CONFIRMATION',0,?,?,0)
        """,
        item,
        payment,
        Timestamp.from(at),
        Timestamp.from(at));
    inbox(item, payment, event, at, duration);
    inbox(item, payment, UUID.randomUUID(), at, 1000); // same fact under another event ID
    jdbc.update(
        """
        INSERT INTO settlement.settlement_entries (id,item_id,payment_id,side,leg,amount,business_ref,
          source_event_id,source_occurred_at,policy_version,created_at)
        SELECT gen_random_uuid(),?,?,'ALLOCATION',leg,0,gen_random_uuid(),?,?, 'v1',?
        FROM (VALUES ('SELLER'),('FEE')) legs(leg)
        """,
        item,
        payment,
        event,
        Timestamp.from(at.minusSeconds(5)),
        Timestamp.from(at));
    return item;
  }

  private void inbox(UUID item, UUID payment, UUID event, Instant at, int duration) {
    jdbc.update(
        """
        INSERT INTO settlement.inbox (id,consumer,event_id,event_type,event_version,occurred_at,
          payload,raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,
          aggregate_version,status,result_ref,order_accepted_at,created_at,updated_at)
        SELECT gen_random_uuid(),'settlement.v1',?,'PaymentConfirmed',1,?,body,
          convert_to(body::text,'UTF8'),encode(sha256(convert_to(body::text,'UTF8')),'hex'),
          'commerce-settle.payment.v1',0,1,?,0,'APPLIED',?,?,?,?
        FROM (SELECT jsonb_build_object('eventId',?::text,'eventType','PaymentConfirmed',
          'eventVersion',1,'occurredAt',?::text,'aggregateId',?::text,'aggregateVersion',0,
          'payload',jsonb_build_object('confirmedAt',?::text)) AS body) json
        """,
        event,
        Timestamp.from(at.minusSeconds(5)),
        payment,
        item,
        Timestamp.from(at.minusSeconds(duration)),
        Timestamp.from(at),
        Timestamp.from(at),
        event,
        at.minusSeconds(5).toString(),
        payment,
        at.minusSeconds(5).toString());
  }

  @Test
  void sourcePopulationShowsMissingItemsAndIncompleteLatestCapture() {
    UUID run = UUID.randomUUID(), payment = UUID.randomUUID();
    try (var metrics = metrics()) {
      metrics.refresh();
      assertThat(cohort(metrics, "capture_complete", "payout.unapplied", "pending")).isZero();
      assertThat(cohort(metrics, "records", "payout.unapplied", "pending")).isNaN();
      jdbc.update(
          """
        INSERT INTO settlement.reconciliation_runs(id,cutoff_at,snapshot_at,stage,status,requested_by)
        VALUES (?,now()-interval '10 minutes',now(),'PAYOUT','WAITING','test')
        """,
          run);
      jdbc.update(
          """
        INSERT INTO settlement.reconciliation_events(run_id,event_id,source,body,status)
        VALUES (?,gen_random_uuid(),'PAYMENT',jsonb_build_object('eventType','PaymentConfirmed',
          'paymentId',?::text,'occurredAt',now()-interval '20 minutes'),'WAIT_DELIVERY')
        """,
          run,
          payment);
      metrics.refresh();
      assertThat(cohort(metrics, "records", "payout.unapplied", "wait_delivery")).isEqualTo(1);
      assertThat(cohort(metrics, "oldest_age_seconds", "payout.unapplied", "wait_delivery"))
          .isGreaterThanOrEqualTo(1200);
      assertThat(cohort(metrics, "capture_complete", "payout.unapplied", "wait_delivery"))
          .isEqualTo(1);
      UUID applied = fact(12);
      jdbc.update(
          "UPDATE settlement.settlement_items SET payment_id=? WHERE id=?", payment, applied);
      metrics.refresh();
      assertThat(cohort(metrics, "records", "payout.unapplied", "wait_delivery")).isZero();
      jdbc.update(
          "UPDATE settlement.reconciliation_runs SET last_error='SOURCE_UNAVAILABLE',status='UNVERIFIED' WHERE id=?",
          run);
      metrics.refresh();
      assertThat(cohort(metrics, "capture_complete", "payout.unapplied", "wait_delivery")).isZero();
      assertThat(cohort(metrics, "records", "payout.unapplied", "wait_delivery")).isNaN();
      jdbc.update(
          "UPDATE settlement.reconciliation_runs SET last_error=NULL,status='RUNNING',stage='PAYMENT' WHERE id=?",
          run);
      metrics.refresh();
      assertThat(cohort(metrics, "capture_complete", "payout.unapplied", "wait_delivery")).isZero();
    }
  }

  private BusinessMetrics metrics() {
    return new BusinessMetrics(
        source,
        null,
        BusinessMetricsConfiguration.sources(),
        BusinessMetricsConfiguration.cohorts());
  }

  @Test
  void unpaidCohortSkipsLargePaidHistory() {
    fact(12);
    jdbc.execute(
        """
        INSERT INTO settlement.settlement_items (id,payment_id,order_id,product_id,seller_id,
          total_amount,platform_coupon_amount,settlement_base_amount,external_amount,mileage_amount,
          fee_amount,net_amount,status,order_version,created_at,updated_at,version)
        SELECT gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
          10000,0,10000,10000,0,1500,8500,'PAID',0,now()-interval '30 days',now(),0
        FROM generate_series(1,20000)
        """);
    UUID run = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO settlement.reconciliation_runs(id,cutoff_at,snapshot_at,stage,status,requested_by) VALUES (?,now()-interval '10 minutes',now(),'CHECK','COMPLETED','test')",
        run);
    jdbc.update(
        """
        INSERT INTO settlement.reconciliation_events(run_id,event_id,source,body,status)
        SELECT ?,gen_random_uuid(),'PAYMENT',jsonb_build_object('eventType','PaymentConfirmed',
          'paymentId',payment_id,'occurredAt',created_at),'MATCH'
        FROM settlement.settlement_items WHERE status='PAID'
        """,
        run);
    jdbc.execute("ANALYZE settlement.reconciliation_events");
    String sourcePlan =
        jdbc.queryForObject(
            "EXPLAIN (ANALYZE, FORMAT JSON) "
                + BusinessMetricsConfiguration.cohorts().get("payout.unapplied"),
            String.class);
    assertThat(sourcePlan).contains("reconciliation_events_unmatched");
    jdbc.execute("ANALYZE settlement.settlement_items");
    for (var query :
        BusinessMetricsConfiguration.cohorts().entrySet().stream()
            .filter(e -> !e.getKey().equals("payout.unapplied"))
            .map(java.util.Map.Entry::getValue)
            .toList()) {
      String plan = jdbc.queryForObject("EXPLAIN (ANALYZE, FORMAT JSON) " + query, String.class);
      assertThat(plan).contains("ix_settlement_items_cohort");
    }
    try (var metrics = metrics()) {
      metrics.refresh();
      assertThat(cohort(metrics, "records", "payout.total", "purchase_pending")).isEqualTo(1);
    }
  }

  @Test
  void payoutCountsEachItemOnceAndKeepsUnpaidAndRefundTerminalCohortsSeparate() {
    UUID paid = fact(12), unpaid = fact(15), refunded = fact(20), missing = fact(25);
    jdbc.update("DELETE FROM settlement.inbox WHERE result_ref=?", missing);
    jdbc.update("UPDATE settlement.settlement_items SET status='REFUNDED' WHERE id=?", refunded);
    jdbc.update(
        "UPDATE settlement.settlement_items SET purchase_confirmed_at=created_at,eligible_received_at=created_at,ready_at=created_at WHERE id=?",
        paid);
    UUID payout = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO settlement.payouts (id,batch_id,seller_id,recipient_account_ref,
          recipient_account_version,funding_account_ref,scenario_version,succeeded_at,amount,
          business_key,status,created_at,updated_at,version)
        SELECT ?,gen_random_uuid(),seller_id,'recipient',0,'funding','v1',created_at+interval '2 seconds',
          net_amount,gen_random_uuid(),'SUCCEEDED',created_at,created_at,0
        FROM settlement.settlement_items WHERE id=?
        """,
        payout,
        paid);
    jdbc.update(
        "UPDATE settlement.settlement_items SET payout_id=?,status='PAID' WHERE id=?",
        payout,
        paid);
    try (var metrics = metrics()) {
      metrics.refresh();
      assertThat(value(metrics, "completions", "payment.payout.succeeded")).isEqualTo(1);
      assertThat(value(metrics, "duration_seconds_sum", "payment.payout.succeeded")).isEqualTo(7);
      assertThat(value(metrics, "duration_seconds_sum", "ready.payout.succeeded")).isEqualTo(2);
      assertThat(cohort(metrics, "records", "payout.total", "purchase_pending")).isEqualTo(2);
      assertThat(cohort(metrics, "records", "payout.total", "refunded")).isEqualTo(1);
      assertThat(cohort(metrics, "missing_origin_records", "payout.total", "purchase_pending"))
          .isEqualTo(1);
      assertThat(cohort(metrics, "records", "payout.ready", "purchase_pending")).isZero();
      assertThat(cohort(metrics, "oldest_age_seconds", "payout.total", "refunded")).isZero();
      assertThat(value(metrics, "completions", "settlement.inbox.applied")).isEqualTo(6);
      assertThat(value(metrics, "duration_seconds_sum", "settlement.inbox.applied")).isEqualTo(30);
    }
  }

  private static double cohort(
      BusinessMetrics metrics, String suffix, String operation, String reason) {
    for (var metric : metrics.collect())
      if (metric.getMetadata().getName().equals("commerce_settle_business_cohort_" + suffix))
        for (var point : ((GaugeSnapshot) metric).getDataPoints())
          if (operation.equals(point.getLabels().get("operation"))
              && reason.equals(point.getLabels().get("reason"))) return point.getValue();
    throw new AssertionError(suffix);
  }

  private static double value(BusinessMetrics metrics, String suffix, String operation) {
    for (var metric : metrics.collect())
      if (metric.getMetadata().getName().equals("commerce_settle_business_" + suffix))
        for (var point : ((GaugeSnapshot) metric).getDataPoints())
          if (operation.equals(point.getLabels().get("operation"))
              && "300".equals(point.getLabels().get("window"))) return point.getValue();
    throw new AssertionError(suffix);
  }
}
