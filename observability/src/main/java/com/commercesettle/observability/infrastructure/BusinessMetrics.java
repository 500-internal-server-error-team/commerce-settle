package com.commercesettle.observability.infrastructure;

import com.commercesettle.observability.RepeatedWarning;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot.GaugeDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Committed owner-DB facts in fixed rolling windows; gauges, never retry counters. */
public final class BusinessMetrics implements MultiCollector, AutoCloseable {
  public static final String ORDER_ACCEPTED_AT_HEADER = "commerce-settle-order-accepted-at";
  private static final int[] WINDOWS = {60, 300, 900};
  private static final double[] BOUNDS = {
    .001, .005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10, 30, 60, 300, 900, 3600, 86400, 604800,
    2592000, 31536000, 1e13
  };
  private static final Logger LOG = LoggerFactory.getLogger(BusinessMetrics.class);
  private static final List<String> NAMES =
      List.of(
          "commerce_settle_business_completions",
          "commerce_settle_business_duration_seconds_count",
          "commerce_settle_business_duration_seconds_sum",
          "commerce_settle_business_duration_missing",
          "commerce_settle_business_observed_timestamp_seconds",
          "commerce_settle_business_duration_bucket_records");
  private final RepeatedWarning refreshFailed = new RepeatedWarning();
  private final DataSource dataSource;
  private final PrometheusRegistry registry;
  private final Map<String, String> queries;
  private final Map<String, String> cohorts;
  private final AtomicReference<Map<String, List<Cohort>>> cohortSnapshot =
      new AtomicReference<>(Map.of());
  private final AtomicReference<Map<String, Map<Integer, Values>>> snapshot =
      new AtomicReference<>(Map.of());
  private final ScheduledExecutorService observer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("business-metrics-", 0).daemon(true).factory());

  /** Each trusted SQL source selects completed_at and nullable started_at, bounded to 900s. */
  public BusinessMetrics(
      DataSource dataSource, PrometheusRegistry registry, Map<String, String> sources) {
    this(dataSource, registry, sources, Map.of());
  }

  public BusinessMetrics(
      DataSource dataSource,
      PrometheusRegistry registry,
      Map<String, String> sources,
      Map<String, String> cohorts) {
    this.dataSource = dataSource;
    this.registry = registry;
    this.queries = Map.copyOf(sources);
    this.cohorts = Map.copyOf(cohorts);
    if (registry != null) registry.register(this);
  }

  @Override
  public List<String> getPrometheusNames() {
    var names = new ArrayList<>(NAMES);
    if (!cohorts.isEmpty())
      names.addAll(
          List.of(
              "commerce_settle_business_cohort_records",
              "commerce_settle_business_cohort_oldest_age_seconds",
              "commerce_settle_business_cohort_missing_origin_records",
              "commerce_settle_business_cohort_observed_timestamp_seconds",
              "commerce_settle_business_cohort_cutoff_timestamp_seconds",
              "commerce_settle_business_cohort_snapshot_timestamp_seconds",
              "commerce_settle_business_cohort_capture_complete"));
    return names;
  }

  @Override
  public MetricSnapshots collect() {
    var current = snapshot.get();
    var points = new ArrayList<List<GaugeDataPointSnapshot>>();
    for (int index = 0; index < NAMES.size(); index++) points.add(new ArrayList<>());
    for (String operation : queries.keySet()) {
      for (int window : WINDOWS) {
        Values value = current.getOrDefault(operation, Map.of()).get(window);
        Labels labels = Labels.of("operation", operation, "window", Integer.toString(window));
        double[] measurements =
            value == null
                ? new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0}
                : new double[] {
                  value.completed(),
                  value.valid(),
                  value.sum(),
                  value.completed() - value.valid(),
                  value.observed()
                };
        for (int index = 0; index < measurements.length; index++)
          points.get(index).add(new GaugeDataPointSnapshot(measurements[index], labels, null));
        for (int index = 0; index <= BOUNDS.length; index++) {
          double count =
              value == null
                  ? Double.NaN
                  : index == BOUNDS.length ? value.valid() : value.buckets()[index];
          points
              .get(5)
              .add(
                  new GaugeDataPointSnapshot(
                      count,
                      Labels.of(
                          "operation",
                          operation,
                          "window",
                          Integer.toString(window),
                          "le",
                          index == BOUNDS.length ? "+Inf" : Double.toString(BOUNDS[index])),
                      null));
        }
      }
    }
    var metrics = new ArrayList<MetricSnapshot>();
    for (int index = 0; index < NAMES.size(); index++)
      metrics.add(
          new GaugeSnapshot(
              new MetricMetadata(
                  NAMES.get(index),
                  "Committed owner DB rolling-window facts; gauges; timestamp zero means unavailable"),
              points.get(index)));
    if (!cohorts.isEmpty()) {
      var currentCohorts = cohortSnapshot.get();
      var cohortNames = getPrometheusNames().subList(NAMES.size(), getPrometheusNames().size());
      for (int index = 0; index < cohortNames.size(); index++) {
        var values = new ArrayList<GaugeDataPointSnapshot>();
        for (String operation : cohorts.keySet()) {
          List<Cohort> rows = currentCohorts.get(operation);
          if (rows == null) {
            values.add(
                new GaugeDataPointSnapshot(
                    index == 3 ? 0 : Double.NaN,
                    Labels.of("operation", operation, "reason", "unavailable"),
                    null));
            continue;
          }
          for (var row : rows) {
            double[] fields = {
              row.records(),
              row.oldest(),
              row.missing(),
              row.observed(),
              row.cutoff(),
              row.snapshot(),
              row.complete()
            };
            values.add(
                new GaugeDataPointSnapshot(
                    fields[index],
                    Labels.of("operation", operation, "reason", row.reason()),
                    null));
          }
        }
        metrics.add(
            new GaugeSnapshot(
                new MetricMetadata(
                    cohortNames.get(index),
                    "Owner DB payout cohorts; terminal refunds are separate from unpaid records"),
                values));
      }
    }
    return new MetricSnapshots(metrics);
  }

  /** Invalid optional metadata never prevents business delivery. */
  public static Instant parseOrderAcceptedAt(byte[] value) {
    if (value == null || value.length > 40) return null;
    try {
      Instant origin = Instant.parse(new String(value, StandardCharsets.US_ASCII));
      return origin.isBefore(Instant.EPOCH)
              || origin.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))
          ? null
          : origin;
    } catch (DateTimeParseException invalid) {
      return null;
    }
  }

  public void start() {
    if (registry == null) return;
    observer.scheduleWithFixedDelay(this::refresh, 0, 15, TimeUnit.SECONDS);
  }

  /** No DB work occurs on the HTTP scrape thread. A failed refresh keeps its old timestamp. */
  public synchronized void refresh() {
    var next = new HashMap<>(snapshot.get());
    for (var source : queries.entrySet()) {
      var windows = new HashMap<Integer, Values>();
      try (Connection connection = dataSource.getConnection()) {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(sql(source.getValue()))) {
          statement.setQueryTimeout(2);
          try (var rows = statement.executeQuery()) {
            while (rows.next()) {
              double[] buckets = new double[BOUNDS.length];
              for (int i = 0; i < buckets.length; i++) buckets[i] = rows.getDouble("b" + i);
              windows.put(
                  rows.getInt("window_seconds"),
                  new Values(
                      rows.getDouble("completed"),
                      rows.getDouble("valid"),
                      rows.getDouble("sum_seconds"),
                      rows.getDouble("observed"),
                      buckets));
            }
            if (windows.size() != WINDOWS.length) throw new SQLException("Incomplete windows");
          }
        } finally {
          connection.rollback();
        }
      } catch (SQLException | RuntimeException failure) {
        refreshFailed.at(LOG).log(
            "Business metric refresh failed for {} ({})",
            source.getKey(),
            failure.getClass().getSimpleName());
        continue;
      }
      next.put(source.getKey(), Map.copyOf(windows));
    }
    snapshot.set(Map.copyOf(next));
    var pending = new HashMap<>(cohortSnapshot.get());
    for (var source : cohorts.entrySet()) {
      var values = new ArrayList<Cohort>();
      try (Connection connection = dataSource.getConnection()) {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(source.getValue())) {
          statement.setQueryTimeout(2);
          try (var rows = statement.executeQuery()) {
            while (rows.next()) {
              String reason = rows.getString("reason");
              if (reason == null || !reason.matches("[a-z_]{1,40}"))
                throw new SQLException("Unbounded cohort reason");
              values.add(
                  new Cohort(
                      reason,
                      rows.getDouble("records"),
                      rows.getDouble("oldest"),
                      rows.getDouble("missing"),
                      rows.getDouble("observed"),
                      rows.getDouble("cutoff"),
                      rows.getDouble("snapshot"),
                      rows.getDouble("complete")));
            }
            if (values.isEmpty()) throw new SQLException("Incomplete cohort snapshot");
          }
        } finally {
          connection.rollback();
        }
      } catch (SQLException | RuntimeException failure) {
        refreshFailed.at(LOG).log(
            "Business cohort refresh failed for {} ({})",
            source.getKey(),
            failure.getClass().getSimpleName());
        continue;
      }
      pending.put(source.getKey(), List.copyOf(values));
    }
    cohortSnapshot.set(Map.copyOf(pending));
  }

  /** Whole committed delivery intervals, independent of execution attempts and trace sampling. */
  public static Map<String, String> deliverySources(String schema) {
    if (!List.of("commerce", "payment", "settlement").contains(schema))
      throw new IllegalArgumentException("Unknown owner schema");
    return Map.of(
        schema + ".outbox.published",
        "SELECT published_at completed_at, created_at started_at FROM "
            + schema
            + ".outbox"
            + " WHERE status='PUBLISHED' AND published_at >= statement_timestamp()-interval '900 seconds'"
            + " AND published_at < statement_timestamp()",
        schema + ".inbox.applied",
        "SELECT updated_at completed_at, occurred_at started_at FROM "
            + schema
            + ".inbox"
            + " WHERE consumer='"
            + schema
            + ".v1' AND status='APPLIED'"
            + " AND updated_at >= statement_timestamp()-interval '900 seconds'"
            + " AND updated_at < statement_timestamp()");
  }

  private static String sql(String source) {
    StringBuilder buckets = new StringBuilder();
    for (int i = 0; i < BOUNDS.length; i++) {
      buckets
          .append(", count(*) FILTER (WHERE elapsed >= 0 AND elapsed <= ")
          .append(BOUNDS[i])
          .append(") AS b")
          .append(i);
    }
    return "WITH measured AS MATERIALIZED ("
        + source
        + "), events AS ("
        + "SELECT completed_at, extract(epoch FROM (completed_at-started_at)) elapsed FROM measured), "
        + "windows(window_seconds) AS (VALUES (60),(300),(900)) "
        + "SELECT window_seconds, extract(epoch FROM statement_timestamp()) observed, "
        + "count(completed_at) completed, count(*) FILTER (WHERE elapsed >= 0) valid, "
        + "coalesce(sum(elapsed) FILTER (WHERE elapsed >= 0),0) sum_seconds"
        + buckets
        + " FROM windows LEFT JOIN events ON completed_at >= statement_timestamp() "
        + "- window_seconds * interval '1 second' AND completed_at < statement_timestamp() "
        + "GROUP BY window_seconds";
  }

  @Override
  public void close() {
    observer.shutdownNow();
    if (registry != null) registry.unregister(this);
  }

  private record Values(
      double completed, double valid, double sum, double observed, double[] buckets) {}

  private record Cohort(
      String reason,
      double records,
      double oldest,
      double missing,
      double observed,
      double cutoff,
      double snapshot,
      double complete) {}
}
