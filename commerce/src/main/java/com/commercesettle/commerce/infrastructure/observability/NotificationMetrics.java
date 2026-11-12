package com.commercesettle.commerce.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** scrape는 DB를 조회하지 않는다. 실패·오래된 표본은 Prometheus에서 별도로 경보한다. */
public final class NotificationMetrics implements AutoCloseable {
  // 지표마다 필요한 행만 인덱스 조건으로 읽는다. 발송 완료(SENT) 이력은 주문과 함께 늘어나므로 훑지 않는다.
  private static final String SQL =
      """
      SELECT extract(epoch FROM statement_timestamp())::double precision AS observed,
        review.total AS review, review.exhausted,
        (SELECT count(*) FROM commerce.notification_deliveries
          WHERE kind IN ('REFUND_ACTION_REQUIRED','PAYOUT_ACTION_REQUIRED')
            AND created_at >= statement_timestamp() - interval '5 minutes') AS action,
        (SELECT coalesce(max(greatest(0, extract(epoch FROM
            (statement_timestamp() - coalesce(bucket_end, created_at))))), 0)::double precision
          FROM commerce.notification_deliveries
          WHERE (status IN ('PENDING','RETRY','UNKNOWN') AND next_run_at <= statement_timestamp())
            OR (status = 'SENDING' AND lease_until <= statement_timestamp())) AS age
      FROM (SELECT count(*) AS total, count(*) FILTER (WHERE retry_count >= 10) AS exhausted
        FROM commerce.notification_deliveries WHERE status = 'REVIEW_REQUIRED') review
      """;
  private final JdbcTemplate jdbc;
  private final AtomicReference<Snapshot> snapshot =
      new AtomicReference<>(new Snapshot(0, 0, 0, 0, 0));
  private final AtomicInteger success = new AtomicInteger();
  private final ScheduledExecutorService observer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("notification-metrics").daemon(true).factory());

  public NotificationMetrics(DataSource dataSource, MeterRegistry registry) {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(1);
    Gauge.builder(
            "commerce.notification.observed.timestamp.seconds",
            snapshot,
            value -> value.get().observed())
        .register(registry);
    Gauge.builder("commerce.notification.collection.success", success, AtomicInteger::get)
        .register(registry);
    Gauge.builder("commerce.notification.review.required", snapshot, value -> value.get().review())
        .register(registry);
    Gauge.builder(
            "commerce.notification.retry.exhausted", snapshot, value -> value.get().exhausted())
        .register(registry);
    Gauge.builder(
            "commerce.notification.financial.action.recent",
            snapshot,
            value -> value.get().action())
        .register(registry);
    Gauge.builder("commerce.notification.overdue.seconds", snapshot, value -> value.get().age())
        .register(registry);
    observer.scheduleWithFixedDelay(this::collect, 0, 15, TimeUnit.SECONDS);
  }

  private void collect() {
    try {
      Snapshot sampled =
          jdbc.queryForObject(
              SQL,
              (row, index) ->
                  new Snapshot(
                      row.getDouble("observed"),
                      row.getLong("review"),
                      row.getLong("exhausted"),
                      row.getLong("action"),
                      row.getDouble("age")));
      if (sampled == null) throw new IllegalStateException("NOTIFICATION_METRICS_UNAVAILABLE");
      snapshot.set(sampled);
      success.set(1);
    } catch (RuntimeException failure) {
      success.set(0);
    }
  }

  @Override
  public void close() {
    observer.shutdownNow();
  }

  private record Snapshot(double observed, long review, long exhausted, long action, double age) {}
}
