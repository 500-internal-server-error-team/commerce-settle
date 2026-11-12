package com.commercesettle.settlement.infrastructure.observability;

import com.commercesettle.settlement.domain.model.InboxReviewReason;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot.GaugeDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 자기 DB만 읽으며 값과 원본 시각을 불변 scrape 하나로 캡처한다. */
public final class FinancialQueueMetrics implements MultiCollector, AutoCloseable {
  private static final String PREFIX = "commerce_settle_";
  private static final List<String> NAMES =
      List.of(
          PREFIX + "financial_queue_records",
          PREFIX + "financial_queue_due_delay_seconds",
          PREFIX + "admission_observed_timestamp_seconds",
          PREFIX + "financial_queue_review_required_records",
          PREFIX + "financial_operation_age_seconds",
          PREFIX + "financial_operation_observed_timestamp_seconds",
          "settlement_inbox_review_records",
          "settlement_inbox_review_oldest_age_seconds",
          "settlement_inbox_observed_timestamp_seconds",
          "settlement_inbox_rejudgement_stalled_seconds");
  private static final List<String> QUEUES =
      List.of("settlement.outbox", "settlement.inbox", "settlement.payout_jobs");
  private static final List<String> OPERATIONS = List.of("settlement.unknown", "settlement.payout");
  private static final List<String> EVENT_TYPES =
      List.of(
          "PaymentConfirmed",
          "PurchaseConfirmed",
          "RefundReservationRequested",
          "RefundFinancialCompleted",
          "RefundReleaseRequested");
  private static final List<String> REASONS =
      java.util.stream.Stream.concat(
              java.util.Arrays.stream(InboxReviewReason.values()).map(Enum::name),
              java.util.stream.Stream.of("UNRECORDED"))
          .toList();
  // 미완료 상태를 나열해 상태 인덱스를 탄다. 완료 상태를 <>로 빼면 처리 완료 이력까지 매번 전부 훑는다.
  private static final String SQL =
      """
      SELECT 'settlement.outbox' AS name, 'queue' AS kind, count(*) AS records,
       coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
       WHEN status IN ('PENDING','RETRY') AND next_run_at <= statement_timestamp() THEN next_run_at
       WHEN status = 'RUNNING' AND lease_until <= statement_timestamp() THEN lease_until END))),0)::double precision AS age,
       count(*) FILTER (WHERE status = 'REVIEW_REQUIRED') AS review,
       extract(epoch FROM statement_timestamp())::double precision AS observed
       FROM settlement.outbox WHERE status IN ('PENDING','RUNNING','RETRY','REVIEW_REQUIRED') AND event_type NOT IN ('PaymentNoticeRequested','SettlementNoticeRequested')
      UNION ALL
      SELECT 'settlement.inbox', 'queue', count(*),
       coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
       WHEN status = 'RECEIVED' THEN created_at
       WHEN status = 'WAITING' AND next_run_at <= statement_timestamp() THEN next_run_at END))),0)::double precision,
       count(*) FILTER (WHERE status = 'REVIEW_REQUIRED'),
       extract(epoch FROM statement_timestamp())::double precision
       FROM settlement.inbox WHERE consumer = ? AND status IN ('RECEIVED','WAITING','REVIEW_REQUIRED')
      UNION ALL
      SELECT 'settlement.payout_jobs' AS name, 'queue' AS kind, count(*) AS records,
       coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
       WHEN status IN ('PENDING','RETRY') AND next_run_at <= statement_timestamp() THEN next_run_at
       WHEN status = 'RUNNING' AND lease_until <= statement_timestamp() THEN lease_until END))),0)::double precision AS age,
       count(*) FILTER (WHERE status = 'REVIEW_REQUIRED') AS review,
       extract(epoch FROM statement_timestamp())::double precision AS observed
       FROM settlement.payout_jobs WHERE status IN ('PENDING','RUNNING','RETRY','REVIEW_REQUIRED')
      UNION ALL
      SELECT 'settlement.unknown', 'operation', count(*),
       coalesce(extract(epoch FROM (statement_timestamp() - min(unknown_since))),0)::double precision,
       0, extract(epoch FROM statement_timestamp())::double precision FROM settlement.payouts WHERE status = 'UNKNOWN'
      UNION ALL
      SELECT 'settlement.payout', 'operation', count(*),
       coalesce(extract(epoch FROM (statement_timestamp() - min(cutoff_at))),0)::double precision,
       0, extract(epoch FROM statement_timestamp())::double precision FROM settlement.settlement_batches WHERE status IN ('RESERVED','PROCESSING','REVIEW_REQUIRED')
      UNION ALL
      SELECT 'review:' || CASE WHEN last_error_code IN (%s) THEN last_error_code ELSE 'UNRECORDED' END
        || ':' || event_type, 'inbox_review', count(*),
        greatest(0,extract(epoch FROM(statement_timestamp()-min(updated_at))))::double precision,
        0,extract(epoch FROM statement_timestamp())::double precision
      FROM settlement.inbox WHERE consumer=? AND status='REVIEW_REQUIRED'
        AND event_type IN ('PaymentConfirmed','PurchaseConfirmed','RefundReservationRequested','RefundFinancialCompleted','RefundReleaseRequested')
      GROUP BY 1
      UNION ALL
      SELECT 'rejudgement','inbox_run',count(*),
        greatest(0,coalesce(extract(epoch FROM(statement_timestamp()-min(updated_at))),0))::double precision,
        0,extract(epoch FROM statement_timestamp())::double precision
      FROM settlement.inbox_rejudgement_runs WHERE consumer=? AND status='RUNNING'
      """
          .formatted(
              REASONS.stream()
                  .map(reason -> "'" + reason + "'")
                  .collect(java.util.stream.Collectors.joining(",")));
  private final String consumer;
  private final JdbcTemplate jdbc;
  private final PrometheusRegistry registry;
  private final AtomicReference<Map<String, Values>> snapshot = new AtomicReference<>(Map.of());
  private final ScheduledExecutorService observer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("settlement-financial-observer-", 0).daemon(true).factory());

  public FinancialQueueMetrics(DataSource dataSource, PrometheusRegistry registry) {
    this(dataSource, registry, "settlement.v1");
  }

  public FinancialQueueMetrics(
      DataSource dataSource, PrometheusRegistry registry, String consumer) {
    this.consumer = consumer;
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(2);
    this.registry = registry;
    if (registry != null) registry.register(this);
  }

  public void start() {
    observer.scheduleWithFixedDelay(this::refresh, 0, 15, TimeUnit.SECONDS);
  }

  public void refresh() {
    try {
      Map<String, Values> current = new HashMap<>();
      for (var row : jdbc.queryForList(SQL, consumer, consumer, consumer)) {
        current.put(
            (String) row.get("name"),
            new Values(
                number(row, "records"),
                number(row, "age"),
                number(row, "review"),
                number(row, "observed")));
      }
      snapshot.set(Map.copyOf(current));
    } catch (DataAccessException exception) {
      snapshot.set(Map.of());
    }
  }

  private static double number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).doubleValue();
  }

  @Override
  public MetricSnapshots collect() {
    var current = snapshot.get();
    var records = new ArrayList<GaugeDataPointSnapshot>();
    var ages = new ArrayList<GaugeDataPointSnapshot>();
    var observed = new ArrayList<GaugeDataPointSnapshot>();
    var review = new ArrayList<GaugeDataPointSnapshot>();
    var operations = new ArrayList<GaugeDataPointSnapshot>();
    var operationTimes = new ArrayList<GaugeDataPointSnapshot>();
    for (String queue : QUEUES) {
      var value = current.getOrDefault(queue, new Values(0, 0, 0, 0));
      records.add(point(value.records(), "queue", queue));
      ages.add(point(value.age(), "queue", queue));
      review.add(point(value.review(), "queue", queue));
      for (String signal : List.of("QUEUE_SIZE", "DUE_AGE"))
        observed.add(
            new GaugeDataPointSnapshot(
                value.observed(), Labels.of("signal", signal, "resource", queue), null));
    }
    for (String operation : OPERATIONS) {
      var value = current.getOrDefault(operation, new Values(0, 0, 0, 0));
      operations.add(point(value.age(), "operation", operation));
      operationTimes.add(point(value.observed(), "operation", operation));
    }
    var reviewCounts = new ArrayList<GaugeDataPointSnapshot>();
    var reviewAges = new ArrayList<GaugeDataPointSnapshot>();
    for (String reason : REASONS)
      for (String eventType : EVENT_TYPES) {
        var value =
            current.getOrDefault("review:" + reason + ":" + eventType, new Values(0, 0, 0, 0));
        var labels = Labels.of("consumer", consumer, "reason", reason, "event_type", eventType);
        reviewCounts.add(new GaugeDataPointSnapshot(value.records(), labels, null));
        reviewAges.add(new GaugeDataPointSnapshot(value.age(), labels, null));
      }
    var run = current.getOrDefault("rejudgement", new Values(0, 0, 0, 0));
    var freshness = List.of(point(run.observed(), "consumer", consumer));
    var stall = List.of(point(run.age(), "consumer", consumer));
    return MetricSnapshots.of(
        metric(0, records),
        metric(1, ages),
        metric(2, observed),
        metric(3, review),
        metric(4, operations),
        metric(5, operationTimes),
        metric(6, reviewCounts),
        metric(7, reviewAges),
        metric(8, freshness),
        metric(9, stall));
  }

  private static GaugeDataPointSnapshot point(double value, String label, String resource) {
    return new GaugeDataPointSnapshot(value, Labels.of(label, resource), null);
  }

  private static GaugeSnapshot metric(int index, List<GaugeDataPointSnapshot> points) {
    return new GaugeSnapshot(
        new MetricMetadata(
            NAMES.get(index), "Owner DB financial observation; timestamp zero means unavailable"),
        points);
  }

  @Override
  public List<String> getPrometheusNames() {
    return NAMES;
  }

  @Override
  public void close() {
    observer.shutdownNow();
    if (registry != null) registry.unregister(this);
  }

  private record Values(double records, double age, double review, double observed) {}
}
