package com.commercesettle.commerce.infrastructure.observability;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot.GaugeDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Commerce 금융 대기열만 수집한다. scrape에서 DB를 호출하지 않는다. */
public final class CommerceQueueMetrics implements MultiCollector, AutoCloseable {
  private static final List<String> NAMES =
      List.of(
          "commerce_settle_financial_queue_records",
          "commerce_settle_financial_queue_due_delay_seconds",
          "commerce_settle_admission_observed_timestamp_seconds",
          "commerce_settle_kafka_group_lag_records",
          "commerce_settle_financial_queue_review_required_records",
          "commerce_settle_financial_operation_age_seconds",
          "commerce_settle_financial_operation_observed_timestamp_seconds");
  // 미완료 상태를 나열해 상태 인덱스를 탄다. 완료 상태를 <>로 빼면 처리 완료 이력까지 매번 전부 훑는다.
  private static final String SQL =
      """
      SELECT 'commerce.outbox' AS queue, count(*) AS records,
        coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
          WHEN status IN ('PENDING','RETRY') AND next_run_at <= statement_timestamp()
            THEN next_run_at
          WHEN status = 'RUNNING' AND lease_until <= statement_timestamp() THEN lease_until
        END))), 0)::double precision AS age,
        extract(epoch FROM statement_timestamp())::double precision AS observed,
        count(*) FILTER (WHERE status = 'REVIEW_REQUIRED') AS review
      FROM commerce.outbox WHERE status IN ('PENDING','RUNNING','RETRY','REVIEW_REQUIRED')
        AND event_type NOT IN ('PaymentNoticeRequested','SettlementNoticeRequested')
      UNION ALL
      SELECT 'commerce.inbox', count(*),
        coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
          WHEN status = 'RECEIVED' THEN created_at
          WHEN status = 'WAITING' AND next_run_at <= statement_timestamp() THEN next_run_at
        END))), 0)::double precision,
        extract(epoch FROM statement_timestamp())::double precision,
        count(*) FILTER (WHERE status = 'REVIEW_REQUIRED')
      FROM commerce.inbox WHERE consumer = 'commerce.v1' AND status IN ('RECEIVED','WAITING','REVIEW_REQUIRED')
      UNION ALL
      SELECT 'commerce.refund', count(*),
        coalesce(extract(epoch FROM (statement_timestamp() - min(accepted_at))),0)::double precision,
        extract(epoch FROM statement_timestamp())::double precision, 0
      FROM commerce.order_refund_requests
      WHERE status IN ('PENDING_SELLER','APPROVED','REFUNDING','FAILED_REVIEW')
      """;
  private final JdbcTemplate jdbc;
  private final PrometheusRegistry registry;
  private final KafkaLagMetrics kafka;
  private final AtomicReference<Map<String, Values>> snapshot = new AtomicReference<>(Map.of());

  public CommerceQueueMetrics(DataSource dataSource, PrometheusRegistry registry) {
    this(dataSource, registry, null);
  }

  public CommerceQueueMetrics(
      DataSource dataSource, PrometheusRegistry registry, KafkaLagMetrics kafka) {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(2);
    this.registry = registry;
    this.kafka = kafka;
    if (registry != null) registry.register(this);
  }

  @Override
  public MetricSnapshots collect() {
    Map<String, Values> current = snapshot.get();
    var records = new ArrayList<GaugeDataPointSnapshot>();
    var ages = new ArrayList<GaugeDataPointSnapshot>();
    var observed = new ArrayList<GaugeDataPointSnapshot>();
    var review = new ArrayList<GaugeDataPointSnapshot>();
    var lags = new ArrayList<GaugeDataPointSnapshot>();
    for (String queue : new String[] {"commerce.outbox", "commerce.inbox"}) {
      Values values = current.getOrDefault(queue, new Values(0, 0, 0, 0));
      records.add(new GaugeDataPointSnapshot(values.records(), Labels.of("queue", queue), null));
      ages.add(new GaugeDataPointSnapshot(values.age(), Labels.of("queue", queue), null));
      review.add(new GaugeDataPointSnapshot(values.review(), Labels.of("queue", queue), null));
      for (String signal : new String[] {"QUEUE_SIZE", "DUE_AGE"}) {
        observed.add(
            new GaugeDataPointSnapshot(
                values.observed(), Labels.of("signal", signal, "resource", queue), null));
      }
    }
    var kafkaSnapshot = kafka == null ? Map.<String, KafkaLagMetrics.Lag>of() : kafka.snapshot();
    if (kafka != null)
      for (String group : KafkaLagMetrics.SUBSCRIPTIONS.keySet()) {
        var value = kafkaSnapshot.getOrDefault(group, new KafkaLagMetrics.Lag(0, 0));
        lags.add(new GaugeDataPointSnapshot(value.records(), Labels.of("group", group), null));
        observed.add(
            new GaugeDataPointSnapshot(
                value.observed(), Labels.of("signal", "LAG", "resource", group), null));
      }
    var refund = current.getOrDefault("commerce.refund", new Values(0, 0, 0, 0));
    return MetricSnapshots.of(
        new GaugeSnapshot(
            new MetricMetadata(NAMES.get(0), "Unfinished financial queue records"), records),
        new GaugeSnapshot(
            new MetricMetadata(NAMES.get(1), "Oldest due financial queue delay in seconds"), ages),
        new GaugeSnapshot(
            new MetricMetadata(
                NAMES.get(2), "Original observation epoch seconds; zero when unavailable"),
            observed),
        new GaugeSnapshot(new MetricMetadata(NAMES.get(3), "All subscribed partition lag"), lags),
        new GaugeSnapshot(
            new MetricMetadata(NAMES.get(4), "Financial manual review records"), review),
        new GaugeSnapshot(
            new MetricMetadata(NAMES.get(5), "Unfinished refund age in seconds"),
            List.of(
                new GaugeDataPointSnapshot(
                    refund.age(), Labels.of("operation", "commerce.refund"), null))),
        new GaugeSnapshot(
            new MetricMetadata(NAMES.get(6), "Original financial observation epoch seconds"),
            List.of(
                new GaugeDataPointSnapshot(
                    refund.observed(), Labels.of("operation", "commerce.refund"), null))));
  }

  @Override
  public List<String> getPrometheusNames() {
    return NAMES;
  }

  public void refresh() {
    try {
      var rows = jdbc.queryForList(SQL);
      var current = new java.util.HashMap<String, Values>();
      for (var row : rows) {
        current.put(
            (String) row.get("queue"),
            new Values(
                ((Number) row.get("records")).doubleValue(),
                ((Number) row.get("age")).doubleValue(),
                ((Number) row.get("observed")).doubleValue(),
                ((Number) row.get("review")).doubleValue()));
      }
      snapshot.set(Map.copyOf(current));
    } catch (DataAccessException e) {
      // 0은 성공 시각이 아니라 무효 표본이다. Prometheus가 scrape해도 신선한 관측이 되지 않는다.
      snapshot.set(Map.of());
    }
  }

  @Override
  public void close() {
    if (registry != null) registry.unregister(this);
  }

  private record Values(double records, double age, double observed, double review) {}
}
