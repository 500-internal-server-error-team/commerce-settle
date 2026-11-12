package com.commercesettle.payment.infrastructure.observability;

import com.commercesettle.observability.RepeatedWarning;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** DB 관측은 전용 실행기에서 수행하고 scrape에는 마지막 성공 표본만 반환한다. */
public final class QuarantineMetrics implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(QuarantineMetrics.class);
  private static final List<String> KINDS = List.of("DLT", "REPLAY");
  private static final List<String> STATUSES =
      List.of("PENDING", "RUNNING", "RETRY", "PUBLISHED", "REVIEW_REQUIRED");
  private static final String SQL =
      """
      SELECT 'ORIGINAL' AS kind, '' AS status, count(*) AS records,
        count(*) FILTER (WHERE created_at >= statement_timestamp() - interval '5 minutes') AS recent,
        0::double precision AS age,
        extract(epoch FROM statement_timestamp())::double precision AS observed
      FROM payment.inbox_quarantines WHERE consumer = ?
      UNION ALL
      SELECT d.kind, d.status, count(*), 0,
        coalesce(extract(epoch FROM (statement_timestamp() - min(CASE
          WHEN d.status IN ('PENDING','RETRY') AND d.next_run_at <= statement_timestamp()
            THEN d.next_run_at
          WHEN d.status = 'RUNNING' AND d.lease_until <= statement_timestamp()
            THEN d.lease_until END))), 0)::double precision,
        extract(epoch FROM statement_timestamp())::double precision
      FROM payment.inbox_quarantine_deliveries d
      JOIN payment.inbox_quarantines q ON q.id = d.quarantine_id
      WHERE q.consumer = ? GROUP BY d.kind, d.status
      """;
  private final RepeatedWarning observationFailed = new RepeatedWarning();

  private final JdbcTemplate jdbc;
  private final String consumer;
  private final long intervalMs;
  private final AtomicReference<Snapshot> snapshot =
      new AtomicReference<>(new Snapshot(0, 0, 0, Map.of(), Map.of()));
  private final AtomicInteger success = new AtomicInteger();
  private final ScheduledExecutorService observer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("quarantine-observation-", 0).daemon(true).factory());

  public QuarantineMetrics(
      DataSource dataSource, MeterRegistry registry, String consumer, long intervalMs) {
    this(template(dataSource), registry, consumer, intervalMs);
  }

  QuarantineMetrics(JdbcTemplate jdbc, MeterRegistry registry, String consumer, long intervalMs) {
    if (consumer == null || consumer.isBlank() || intervalMs < 1000) {
      throw new IllegalArgumentException("Invalid quarantine observation settings");
    }
    this.jdbc = jdbc;
    this.consumer = consumer;
    this.intervalMs = intervalMs;
    gauge(registry, "originals", () -> snapshot.get().originals());
    gauge(registry, "captured.recent", () -> snapshot.get().recent());
    gauge(registry, "observed.timestamp.seconds", () -> snapshot.get().observed());
    gauge(registry, "collection.success", success::doubleValue);
    for (String kind : KINDS) {
      Gauge.builder(
              "payment.quarantine.delivery.overdue.seconds",
              this,
              self -> self.snapshot.get().ages().getOrDefault(kind, 0.0))
          .tags("consumer", consumer, "kind", kind)
          .register(registry);
      for (String status : STATUSES) {
        Gauge.builder(
                "payment.quarantine.delivery.records",
                this,
                self -> self.snapshot.get().records().getOrDefault(kind + ":" + status, 0.0))
            .tags("consumer", consumer, "kind", kind, "status", status)
            .register(registry);
      }
    }
  }

  private static JdbcTemplate template(DataSource dataSource) {
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(2);
    return jdbc;
  }

  private void gauge(MeterRegistry registry, String name, java.util.function.DoubleSupplier value) {
    Gauge.builder(
            "payment.quarantine." + name, value, java.util.function.DoubleSupplier::getAsDouble)
        .tag("consumer", consumer)
        .strongReference(true)
        .register(registry);
  }

  public void start() {
    observer.scheduleWithFixedDelay(this::refresh, 0, intervalMs, TimeUnit.MILLISECONDS);
  }

  public synchronized void refresh() {
    try {
      var rows = jdbc.queryForList(SQL, consumer, consumer);
      var records = new HashMap<String, Double>();
      var ages = new HashMap<String, Double>();
      double originals = 0;
      double recent = 0;
      double observed = 0;
      for (var row : rows) {
        String kind = (String) row.get("kind");
        observed = number(row, "observed");
        if ("ORIGINAL".equals(kind)) {
          originals = number(row, "records");
          recent = number(row, "recent");
        } else {
          records.put(kind + ":" + row.get("status"), number(row, "records"));
          ages.merge(kind, number(row, "age"), Math::max);
        }
      }
      snapshot.set(
          new Snapshot(originals, recent, observed, Map.copyOf(records), Map.copyOf(ages)));
      success.set(1);
    } catch (DataAccessException exception) {
      success.set(0);
      observationFailed
          .at(LOG)
          .addKeyValue("event", "quarantine.observation-failed")
          .log("Quarantine observation retains the last successful sample");
    }
  }

  private static double number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).doubleValue();
  }

  @Override
  public void close() {
    observer.shutdownNow();
    try {
      if (!observer.awaitTermination(5, TimeUnit.SECONDS)) {
        LOG.atWarn()
            .addKeyValue("event", "quarantine.observation-shutdown-pending")
            .log("Quarantine observation has not stopped within the shutdown budget");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private record Snapshot(
      double originals,
      double recent,
      double observed,
      Map<String, Double> records,
      Map<String, Double> ages) {}
}
