package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FinancialQueueMetricsIntegrationTest {
  @Test
  void refundUnknownCountsCurrentRowsWithoutASeparateAgeTimestamp() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(source);
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO payment.refunds (id,payment_id,order_refund_request_id,external_amount,mileage_amount,
          status,business_key,external_status,created_at,updated_at,version)
        VALUES (?,gen_random_uuid(),gen_random_uuid(),1000,0,'UNKNOWN',gen_random_uuid(),'UNKNOWN',
          statement_timestamp()-interval '1000 seconds',statement_timestamp(),0)
        """,
        id);
    try (var metrics = new FinancialQueueMetrics(source, null)) {
      metrics.refresh();
      assertThat(
              value(
                  metrics.collect(), "financial_operation_records", "operation", "refund.unknown"))
          .isEqualTo(1);
      for (var family : metrics.collect())
        if (family.getMetadata().getName().endsWith("financial_operation_age_seconds"))
          assertThat(((GaugeSnapshot) family).getDataPoints())
              .noneMatch(point -> "refund.unknown".equals(point.getLabels().get("operation")));
      jdbc.update(
          "UPDATE payment.refunds SET updated_at=statement_timestamp(),version=version+1 WHERE id=?",
          id);
      metrics.refresh();
      assertThat(
              value(
                  metrics.collect(), "financial_operation_records", "operation", "refund.unknown"))
          .isEqualTo(1);
      jdbc.update(
          "UPDATE payment.refunds SET status='DECLINED',external_status='DECLINED' WHERE id=?", id);
      metrics.refresh();
      assertThat(
              value(
                  metrics.collect(), "financial_operation_records", "operation", "refund.unknown"))
          .isZero();
    } finally {
      jdbc.update("DELETE FROM payment.refunds WHERE id=?", id);
    }
  }

  private static final int HISTORY = 20_000;

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

  @Test
  @DisplayName("처리 완료 이력이 많아도 큐 지표는 테이블 전체를 훑지 않는다")
  void skipsCompletedHistory() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(source);
    List<String> tables = List.of("payment.outbox", "payment.inbox", "payment.payment_jobs");
    tables.forEach(table -> jdbc.update("DELETE FROM " + table));
    try {
      outbox(jdbc, HISTORY, "PUBLISHED");
      outbox(jdbc, 2, "PENDING");
      inbox(jdbc, HISTORY, "APPLIED");
      inbox(jdbc, 2, "WAITING");
      jobs(jdbc, HISTORY, "DONE");
      jobs(jdbc, 2, "PENDING");
      tables.forEach(table -> jdbc.execute("ANALYZE " + table));

      // 지표 조회가 같은 트랜잭션 연결을 쓰게 해서 그 트랜잭션의 스캔 통계만 본다.
      List<Long> scans =
          new TransactionTemplate(new DataSourceTransactionManager(source))
              .execute(
                  status -> {
                    try (var metrics = new FinancialQueueMetrics(source, null)) {
                      metrics.refresh();
                      var snapshot = metrics.collect();
                      for (String table : tables)
                        assertThat(value(snapshot, "financial_queue_records", "queue", table))
                            .isEqualTo(2);
                    }
                    return tables.stream()
                        .map(
                            table ->
                                jdbc.queryForObject(
                                    "SELECT pg_stat_get_xact_numscans(?::regclass)",
                                    Long.class,
                                    table))
                        .toList();
                  });

      assertThat(scans).containsOnly(0L);
    } finally {
      tables.forEach(table -> jdbc.update("DELETE FROM " + table));
    }
  }

  private void outbox(JdbcTemplate jdbc, int rows, String status) {
    boolean published = "PUBLISHED".equals(status);
    jdbc.update(
        """
        INSERT INTO payment.outbox (id,event_type,event_version,aggregate_id,aggregate_version,topic,
            occurred_at,message_key,payload,raw_body,status,published_partition,published_offset,published_at)
        SELECT e.id,'PaymentAccepted',1,e.aggregate,0,'commerce-settle.payment.v1',e.occurred,
            e.aggregate::text,e.body,convert_to(e.body::text,'UTF8'),?,
            CASE WHEN ? THEN 0 END,CASE WHEN ? THEN e.row_no END,CASE WHEN ? THEN e.occurred END
        FROM generate_series(1, ?) n
        CROSS JOIN LATERAL (%s) e
        """
            .formatted(envelope("PaymentAccepted")),
        status,
        published,
        published,
        published,
        rows);
  }

  private void inbox(JdbcTemplate jdbc, int rows, String status) {
    jdbc.update(
        """
        INSERT INTO payment.inbox (id,consumer,event_id,event_type,event_version,occurred_at,payload,
            raw_body,body_hash,source_topic,source_partition,source_offset,aggregate_id,aggregate_version,
            status,next_run_at)
        SELECT gen_random_uuid(),'payment.v1',e.id,'OrderPaymentRequested',1,e.occurred,e.body,
            convert_to(e.body::text,'UTF8'),repeat('0',64),'commerce-settle.commerce.v1',0,e.row_no,
            e.aggregate,0,?,CASE WHEN ? = 'WAITING' THEN e.occurred END
        FROM generate_series(1, ?) n
        CROSS JOIN LATERAL (%s) e
        """
            .formatted(envelope("OrderPaymentRequested")),
        status,
        status,
        rows);
  }

  private void jobs(JdbcTemplate jdbc, int rows, String status) {
    jdbc.update(
        """
        INSERT INTO payment.payment_jobs (id,payment_id,business_key,status,attempts,next_run_at,
            created_at,updated_at,version,operation)
        SELECT gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),?,0,now(),now(),now(),0,'APPROVE'
        FROM generate_series(1, ?)
        """,
        status,
        rows);
  }

  private static String envelope(String eventType) {
    return """
        SELECT ids.id, ids.aggregate, ids.occurred, n AS row_no, jsonb_build_object(
            'eventId',ids.id,'eventType','%s','eventVersion',1,
            'occurredAt','2026-11-10T00:00:00Z','aggregateId',ids.aggregate,'aggregateVersion',0,
            'payload',jsonb_build_object('paymentId',ids.aggregate)) AS body
        FROM (SELECT gen_random_uuid() AS id, gen_random_uuid() AS aggregate,
            timestamptz '2026-11-10 00:00:00+00' AS occurred, n) ids
        """
        .formatted(eventType);
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
