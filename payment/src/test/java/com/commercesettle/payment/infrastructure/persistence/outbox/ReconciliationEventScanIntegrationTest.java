package com.commercesettle.payment.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.port.ReconciliationEventReader.ReconciliationEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 다른 이벤트가 많이 쌓여도 정산 대조 피드가 금융 이벤트 부분 인덱스만 훑는지 스캔 통계로 확인한다. */
@Testcontainers
@Timeout(120)
class ReconciliationEventScanIntegrationTest {
  private static final Instant CUTOFF = Instant.parse("2026-11-10T01:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Test
  @DisplayName("대조 피드 페이지는 Outbox 전체를 훑지 않고 금융 이벤트 인덱스를 역순으로 읽는다")
  void readsPageThroughReconciliationIndex() {
    DataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(source);
    var reader = new JdbcReconciliationEventReader(new NamedParameterJdbcTemplate(jdbc));
    insert(jdbc, 20_000, "PaymentAccepted");
    insert(jdbc, 3, "PaymentConfirmed");
    jdbc.execute("ANALYZE payment.outbox");

    var result =
        new TransactionTemplate(new DataSourceTransactionManager(source))
            .execute(
                status -> {
                  List<ReconciliationEvent> page =
                      reader.read(CUTOFF, Instant.parse("2026-12-01T00:00:00Z"), null, 2);
                  return Map.of(
                      "page",
                      page,
                      "scans",
                      jdbc.queryForList(
                          """
                          SELECT CASE c.relkind WHEN 'r' THEN 'table' ELSE c.relname END AS name,
                              pg_stat_get_xact_numscans(c.oid) AS scans
                          FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                          WHERE n.nspname='payment' AND (c.relname='outbox' OR c.oid IN (
                              SELECT indexrelid FROM pg_index
                              WHERE indrelid='payment.outbox'::regclass))
                          """));
                });

    assertThat((List<?>) result.get("page")).hasSize(2);
    @SuppressWarnings("unchecked")
    var scans = (List<Map<String, Object>>) result.get("scans");
    assertThat(count(scans, "table")).isZero();
    assertThat(count(scans, "ix_outbox_reconciliation")).isPositive();
  }

  private static long count(List<Map<String, Object>> scans, String name) {
    return scans.stream()
        .filter(row -> name.equals(row.get("name")))
        .mapToLong(row -> ((Number) row.get("scans")).longValue())
        .sum();
  }

  private static void insert(JdbcTemplate jdbc, int rows, String type) {
    jdbc.update(
        """
        INSERT INTO payment.outbox (id,event_type,event_version,aggregate_id,aggregate_version,topic,
            occurred_at,message_key,payload,raw_body)
        SELECT e.id,?,1,e.aggregate,0,'commerce-settle.payment.v1',e.occurred,
            e.aggregate::text,e.body,convert_to(e.body::text,'UTF8')
        FROM generate_series(1, ?) n
        CROSS JOIN LATERAL (SELECT gen_random_uuid() AS id, gen_random_uuid() AS aggregate,
            timestamptz '2026-11-10 00:00:00+00' AS occurred, n AS row_no) ids
        CROSS JOIN LATERAL (SELECT ids.id, ids.aggregate, ids.occurred, jsonb_build_object(
            'eventId',ids.id,'eventType',?,'eventVersion',1,
            'occurredAt','2026-11-10T00:00:00Z','aggregateId',ids.aggregate,'aggregateVersion',0,
            'payload',jsonb_build_object('paymentId',ids.aggregate,'externalAmount',1000,
                'mileageAmount',0,'platformCouponAmount',0,'settlementBaseAmount',1000,
                'feeAmount',100,'sellerNetAmount',900)) AS body) e
        """,
        type,
        rows,
        type);
  }
}
