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
          "commerce_settle_admission_observed_timestamp_seconds");
  private static final String SQL =
      """
      SELECT 'commerce.outbox' AS queue, count(*) AS records,
        coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
          WHEN status IN ('PENDING','RETRY') AND next_run_at <= statement_timestamp()
            THEN next_run_at
          WHEN status = 'RUNNING' AND lease_until <= statement_timestamp() THEN lease_until
        END))), 0)::double precision AS age,
        extract(epoch FROM statement_timestamp())::double precision AS observed
      FROM commerce.outbox WHERE status <> 'PUBLISHED'
        AND event_type NOT IN ('PaymentNoticeRequested','SettlementNoticeRequested')
      UNION ALL
      SELECT 'commerce.inbox', count(*),
        coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
          WHEN status = 'RECEIVED' THEN created_at
          WHEN status = 'WAITING' AND next_run_at <= statement_timestamp() THEN next_run_at
        END))), 0)::double precision,
        extract(epoch FROM statement_timestamp())::double precision
      FROM commerce.inbox WHERE consumer = 'commerce.v1' AND status <> 'APPLIED'
      """;
  private final JdbcTemplate jdbc;
  private final PrometheusRegistry registry;
  private final AtomicReference<Map<String, Values>> snapshot = new AtomicReference<>(Map.of());

  public CommerceQueueMetrics(DataSource dataSource, PrometheusRegistry registry) {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(2);
    this.registry = registry;
    if (registry != null) registry.register(this);
  }

  @Override
  public MetricSnapshots collect() {
    Map<String, Values> current = snapshot.get();
    var records = new ArrayList<GaugeDataPointSnapshot>();
    var ages = new ArrayList<GaugeDataPointSnapshot>();
    var observed = new ArrayList<GaugeDataPointSnapshot>();
    for (String queue : new String[] {"commerce.outbox", "commerce.inbox"}) {
      Values values = current.getOrDefault(queue, new Values(0, 0, 0));
      records.add(new GaugeDataPointSnapshot(values.records(), Labels.of("queue", queue), null));
      ages.add(new GaugeDataPointSnapshot(values.age(), Labels.of("queue", queue), null));
      for (String signal : new String[] {"QUEUE_SIZE", "DUE_AGE"}) {
        observed.add(
            new GaugeDataPointSnapshot(
                values.observed(), Labels.of("signal", signal, "resource", queue), null));
      }
    }
    return MetricSnapshots.of(
        new GaugeSnapshot(
            new MetricMetadata(NAMES.get(0), "Unfinished financial queue records"), records),
        new GaugeSnapshot(
            new MetricMetadata(NAMES.get(1), "Oldest due financial queue delay in seconds"), ages),
        new GaugeSnapshot(
            new MetricMetadata(
                NAMES.get(2), "Original observation epoch seconds; zero when unavailable"),
            observed));
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
                ((Number) row.get("observed")).doubleValue()));
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

  private record Values(double records, double age, double observed) {}
}
