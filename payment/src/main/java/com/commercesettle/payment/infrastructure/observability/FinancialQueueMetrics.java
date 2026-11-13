package com.commercesettle.payment.infrastructure.observability;

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
          PREFIX + "financial_queue_records", PREFIX + "financial_queue_due_delay_seconds",
          PREFIX + "admission_observed_timestamp_seconds",
              PREFIX + "financial_queue_review_required_records",
          PREFIX + "financial_operation_age_seconds",
              PREFIX + "financial_operation_observed_timestamp_seconds");
  private static final List<String> QUEUES =
      List.of("payment.outbox", "payment.inbox", "payment.payment_jobs");
  private static final List<String> OPERATIONS = List.of("payment.unknown", "refund.unknown");
  // 미완료 상태를 나열해 상태 인덱스를 탄다. 완료 상태를 <>로 빼면 처리 완료 이력까지 매번 전부 훑는다.
  private static final String SQL =
      """
      SELECT 'payment.outbox' AS name, 'queue' AS kind, count(*) AS records,
       coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
       WHEN status IN ('PENDING','RETRY') AND next_run_at <= statement_timestamp() THEN next_run_at
       WHEN status = 'RUNNING' AND lease_until <= statement_timestamp() THEN lease_until END))),0)::double precision AS age,
       count(*) FILTER (WHERE status = 'REVIEW_REQUIRED') AS review,
       extract(epoch FROM statement_timestamp())::double precision AS observed
       FROM payment.outbox WHERE status IN ('PENDING','RUNNING','RETRY','REVIEW_REQUIRED') AND event_type NOT IN ('PaymentNoticeRequested','SettlementNoticeRequested')
      UNION ALL
      SELECT 'payment.inbox', 'queue', count(*),
       coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
       WHEN status = 'RECEIVED' THEN created_at
       WHEN status = 'WAITING' AND next_run_at <= statement_timestamp() THEN next_run_at END))),0)::double precision,
       count(*) FILTER (WHERE status = 'REVIEW_REQUIRED'),
       extract(epoch FROM statement_timestamp())::double precision
       FROM payment.inbox WHERE consumer = 'payment.v1' AND status IN ('RECEIVED','WAITING','REVIEW_REQUIRED')
      UNION ALL
      SELECT 'payment.payment_jobs' AS name, 'queue' AS kind, count(*) AS records,
       coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
       WHEN status IN ('PENDING','RETRY') AND next_run_at <= statement_timestamp() THEN next_run_at
       WHEN status = 'RUNNING' AND lease_until <= statement_timestamp() THEN lease_until END))),0)::double precision AS age,
       count(*) FILTER (WHERE status = 'REVIEW_REQUIRED') AS review,
       extract(epoch FROM statement_timestamp())::double precision AS observed
       FROM payment.payment_jobs WHERE status IN ('PENDING','RUNNING','RETRY','REVIEW_REQUIRED')
      UNION ALL
      SELECT 'payment.unknown', 'operation', count(*),
       coalesce(extract(epoch FROM (statement_timestamp() - min(updated_at))),0)::double precision,
       0, extract(epoch FROM statement_timestamp())::double precision FROM payment.payments WHERE status = 'UNKNOWN'
      UNION ALL
      SELECT 'refund.unknown', 'operation', count(*),
       coalesce(extract(epoch FROM (statement_timestamp() - min(unknown_since))),0)::double precision,
       0, extract(epoch FROM statement_timestamp())::double precision FROM payment.refunds WHERE status = 'UNKNOWN'
      """;
  private final JdbcTemplate jdbc;
  private final PrometheusRegistry registry;
  private final AtomicReference<Map<String, Values>> snapshot = new AtomicReference<>(Map.of());
  private final ScheduledExecutorService observer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("payment-financial-observer-", 0).daemon(true).factory());

  public FinancialQueueMetrics(DataSource dataSource, PrometheusRegistry registry) {
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
      for (var row : jdbc.queryForList(SQL)) {
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
    return MetricSnapshots.of(
        metric(0, records),
        metric(1, ages),
        metric(2, observed),
        metric(3, review),
        metric(4, operations),
        metric(5, operationTimes));
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
