package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FinancialQueueMetricsIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Test
  @DisplayName("미완료 작업은 모두 세고 미래 대기·유효 임대·수동 확인은 도래 지연과 구분한다")
  void observesOwnerRowsAndInvalidatesFailedCollection() throws Exception {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(source);
    Instant now =
        jdbc.queryForObject("select statement_timestamp()", java.sql.Timestamp.class).toInstant();
    UUID due = job(jdbc, "PENDING", now.plusSeconds(3600), null);
    job(jdbc, "RUNNING", now.minusSeconds(3600), now.plusSeconds(3600));
    job(jdbc, "REVIEW_REQUIRED", now.minusSeconds(3600), null);
    job(jdbc, "DONE", now.minusSeconds(3600), null);
    try (var metrics = new FinancialQueueMetrics(source, null)) {
      metrics.refresh();
      var first = metrics.collect();
      assertThat(value(first, "financial_queue_records", "queue", "payment.payment_jobs"))
          .isEqualTo(3);
      assertThat(value(first, "financial_queue_due_delay_seconds", "queue", "payment.payment_jobs"))
          .isZero();
      assertThat(
              value(
                  first,
                  "financial_queue_review_required_records",
                  "queue",
                  "payment.payment_jobs"))
          .isEqualTo(1);
      assertThat(
              value(
                  first,
                  "admission_observed_timestamp_seconds",
                  "resource",
                  "payment.payment_jobs"))
          .isPositive();
      jdbc.update(
          "update payment.payment_jobs set next_run_at=? where id=?",
          java.sql.Timestamp.from(now.minusSeconds(65)),
          due);
      metrics.refresh();
      var second = metrics.collect();
      double observed =
          value(second, "admission_observed_timestamp_seconds", "resource", "payment.payment_jobs");
      double age =
          value(second, "financial_queue_due_delay_seconds", "queue", "payment.payment_jobs");
      assertThat(age).isGreaterThanOrEqualTo(65);
      assertThat(observed - age)
          .isCloseTo(
              now.minusSeconds(65).getEpochSecond() + now.getNano() / 1e9,
              org.assertj.core.data.Offset.offset(0.000001));
      assertThat(value(first, "financial_queue_due_delay_seconds", "queue", "payment.payment_jobs"))
          .isZero();
      try (var holder = source.getConnection()) {
        holder.setAutoCommit(false);
        holder
            .createStatement()
            .execute("LOCK TABLE payment.payment_jobs IN ACCESS EXCLUSIVE MODE");
        metrics.refresh();
        assertThat(
                value(
                    metrics.collect(),
                    "admission_observed_timestamp_seconds",
                    "resource",
                    "payment.payment_jobs"))
            .isZero();
        holder.rollback();
      }
      metrics.refresh();
      assertThat(
              value(metrics.collect(), "financial_queue_records", "queue", "payment.payment_jobs"))
          .isEqualTo(3);
      assertThat(
              value(
                  metrics.collect(),
                  "admission_observed_timestamp_seconds",
                  "resource",
                  "payment.payment_jobs"))
          .isPositive();
    }
  }

  private UUID job(JdbcTemplate jdbc, String status, Instant next, Instant lease) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into payment.payment_jobs (id,payment_id,business_key,status,attempts,next_run_at,lease_owner,lease_token,lease_until,created_at,updated_at,version,operation) values (?,?,?,?,0,?,?,?,?,now(),now(),0,'APPROVE')",
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        status,
        java.sql.Timestamp.from(next),
        lease == null ? null : "worker",
        lease == null ? null : UUID.randomUUID(),
        lease == null ? null : java.sql.Timestamp.from(lease));
    return id;
  }

  private double value(MetricSnapshots snapshots, String name, String label, String resource) {
    for (var metric : snapshots)
      if (metric.getMetadata().getName().equals("commerce_settle_" + name))
        for (var point : ((GaugeSnapshot) metric).getDataPoints())
          if (resource.equals(point.getLabels().get(label))) return point.getValue();
    throw new AssertionError("Missing metric " + name + " " + resource);
  }
}
