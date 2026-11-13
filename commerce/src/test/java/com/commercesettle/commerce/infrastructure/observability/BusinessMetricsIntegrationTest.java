package com.commercesettle.commerce.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.infrastructure.config.BusinessMetricsConfiguration;
import com.commercesettle.observability.infrastructure.BusinessMetrics;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BusinessMetricsIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/commerce.sql");

  private final DriverManagerDataSource source =
      new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  private final JdbcTemplate jdbc = new JdbcTemplate(source);

  @Test
  void deliveryCountsCommittedFactsAndUsesEventOriginAcrossRestart() {
    var completed =
        jdbc.queryForObject(
            "SELECT statement_timestamp()-interval '2 seconds'", OffsetDateTime.class);
    seed(completed);
    try (var metrics = metrics()) {
      metrics.refresh();
      assertCompletion(metrics, 0);
      new TransactionTemplate(new DataSourceTransactionManager(source))
          .executeWithoutResult(
              tx -> {
                jdbc.update(
                    "UPDATE commerce.outbox SET status='PUBLISHED',published_partition=0,"
                        + "published_offset=0,published_at=? WHERE status='PENDING'",
                    completed);
                jdbc.update(
                    "UPDATE commerce.inbox SET status='APPLIED',updated_at=? WHERE status='WAITING'",
                    completed);
                metrics.refresh();
                assertCompletion(metrics, 0);
              });
      metrics.refresh();
      metrics.refresh();
      assertCompletion(metrics, 1);
      assertThat(value(metrics, "duration_seconds_sum", "commerce.outbox.published")).isEqualTo(12);
      assertThat(value(metrics, "duration_seconds_sum", "commerce.inbox.applied")).isEqualTo(15);
    }
    try (var restarted = metrics()) {
      restarted.refresh();
      assertCompletion(restarted, 1);
      assertThat(value(restarted, "duration_seconds_sum", "commerce.inbox.applied")).isEqualTo(15);
    }
  }

  private void seed(OffsetDateTime completed) {
    jdbc.update(
        """
        WITH facts AS (SELECT gen_random_uuid() event,gen_random_uuid() aggregate,
          CAST(? AS timestamptz)-interval '12 seconds' origin_at),
        bodies AS (SELECT *,jsonb_build_object('eventId',event,'eventType','OrderPaymentRequested',
          'eventVersion',1,'occurredAt',origin_at,'aggregateId',aggregate,'aggregateVersion',0,
          'payload','{}'::jsonb) body FROM facts)
        INSERT INTO commerce.outbox (id,event_type,event_version,aggregate_id,aggregate_version,
          topic,occurred_at,message_key,payload,raw_body,status,created_at,updated_at)
        SELECT event,'OrderPaymentRequested',1,aggregate,0,'commerce-settle.commerce.v1',origin_at,
          aggregate::text,body,convert_to(body::text,'UTF8'),'PENDING',origin_at,origin_at FROM bodies
        """,
        completed);
    jdbc.update(
        """
        WITH facts AS (SELECT gen_random_uuid() event,gen_random_uuid() aggregate,
          CAST(? AS timestamptz)-interval '15 seconds' origin_at),
        bodies AS (SELECT *,jsonb_build_object('eventId',event,'eventType','PaymentAccepted',
          'eventVersion',1,'occurredAt',origin_at,'aggregateId',aggregate,'aggregateVersion',0,
          'payload','{}'::jsonb) body FROM facts)
        INSERT INTO commerce.inbox (id,consumer,event_id,event_type,event_version,occurred_at,
          payload,raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,
          aggregate_version,status,next_run_at,created_at,updated_at)
        SELECT gen_random_uuid(),'commerce.v1',event,'PaymentAccepted',1,origin_at,body,
          convert_to(body::text,'UTF8'),encode(sha256(convert_to(body::text,'UTF8')),'hex'),
          'commerce-settle.payment.v1',0,0,aggregate,0,'WAITING',origin_at+interval '12 seconds',
          origin_at+interval '12 seconds',origin_at+interval '12 seconds' FROM bodies
        """,
        completed);
  }

  private BusinessMetrics metrics() {
    return new BusinessMetrics(source, null, BusinessMetricsConfiguration.sources());
  }

  private static void assertCompletion(BusinessMetrics metrics, int expected) {
    for (var operation : BusinessMetricsConfiguration.sources().keySet()) {
      assertThat(value(metrics, "completions", operation)).isEqualTo(expected);
      assertThat(value(metrics, "duration_seconds_count", operation)).isEqualTo(expected);
    }
  }

  private static double value(BusinessMetrics metrics, String suffix, String operation) {
    for (var metric : metrics.collect())
      if (metric.getMetadata().getName().equals("commerce_settle_business_" + suffix))
        for (var point : ((GaugeSnapshot) metric).getDataPoints())
          if (operation.equals(point.getLabels().get("operation"))
              && "60".equals(point.getLabels().get("window"))) return point.getValue();
    throw new AssertionError(suffix + ":" + operation);
  }
}
