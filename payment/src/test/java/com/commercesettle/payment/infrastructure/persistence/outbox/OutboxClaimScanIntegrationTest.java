package com.commercesettle.payment.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.application.port.OutboxStore;
import com.commercesettle.payment.application.port.OutboxStore.Delivery;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 발행 완료 이력이 쌓여도 선점이 미발행 행만 보는지 같은 트랜잭션의 스캔 통계로 확인한다. */
@Testcontainers
@SpringJUnitConfig(OutboxClaimScanIntegrationTest.Config.class)
@DirtiesContext
@Timeout(120)
class OutboxClaimScanIntegrationTest {
  private static final int PUBLISHED = 20_000;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3")
          .withInitScript("db/payment.sql")
          .withCommand("postgres", "-c", "max_connections=20")
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  @Autowired private OutboxStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager manager;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM payment.outbox");
  }

  @Test
  @DisplayName("발행 완료 행이 많아도 선점은 테이블 전체나 순번 인덱스를 훑지 않는다")
  void claimSkipsPublishedHistory() {
    insert(PUBLISHED, "PUBLISHED");
    insert(3, "PENDING");
    jdbc.execute("ANALYZE payment.outbox");

    var scans =
        new TransactionTemplate(manager)
            .execute(
                status -> {
                  // 미발행 행이 수십만 건이면 플래너는 정렬 대신 sequence_no 순서 인덱스를 고른다.
                  // 작은 시험 데이터에서도 그 계획을 고르도록 정렬을 막는다.
                  jdbc.execute("SET LOCAL enable_sort = off");
                  List<Delivery> claimed = store.claim(32, 30_000, 10);
                  assertThat(claimed).hasSize(3);
                  return jdbc.queryForList(
                      """
                      SELECT CASE c.relkind WHEN 'r' THEN 'table' ELSE c.relname END AS name,
                          pg_stat_get_xact_numscans(c.oid) AS scans
                      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                      WHERE n.nspname='payment'
                        AND (c.relname='outbox' OR c.oid IN (SELECT indexrelid FROM pg_index
                            WHERE indrelid='payment.outbox'::regclass))
                      """);
                });

    assertThat(count(scans, "table")).isZero();
    assertThat(count(scans, "outbox_sequence_no_key")).isZero();
  }

  private static long count(List<Map<String, Object>> scans, String name) {
    return scans.stream()
        .filter(row -> name.equals(row.get("name")))
        .mapToLong(row -> ((Number) row.get("scans")).longValue())
        .sum();
  }

  private void insert(int rows, String status) {
    boolean published = "PUBLISHED".equals(status);
    jdbc.update(
        """
        INSERT INTO payment.outbox (id,event_type,event_version,aggregate_id,aggregate_version,topic,
            occurred_at,message_key,payload,raw_body,status,published_partition,published_offset,published_at)
        SELECT e.id,'PaymentAccepted',1,e.aggregate,0,'commerce-settle.payment.v1',e.occurred,
            e.aggregate::text,e.body,convert_to(e.body::text,'UTF8'),?,
            CASE WHEN ? THEN 0 END,CASE WHEN ? THEN n END,CASE WHEN ? THEN e.occurred END
        FROM generate_series(1, ?) n
        CROSS JOIN LATERAL (SELECT gen_random_uuid() AS id, gen_random_uuid() AS aggregate,
            timestamptz '2026-11-10 00:00:00+00' AS occurred, n AS row_no) ids
        CROSS JOIN LATERAL (SELECT ids.id, ids.aggregate, ids.occurred, jsonb_build_object(
            'eventId',ids.id,'eventType','PaymentAccepted','eventVersion',1,
            'occurredAt','2026-11-10T00:00:00Z','aggregateId',ids.aggregate,'aggregateVersion',0,
            'payload',jsonb_build_object('paymentId',ids.aggregate)) AS body) e
        """,
        status,
        published,
        published,
        published,
        rows);
  }

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DataSource dataSource() {
      String url = POSTGRES.getJdbcUrl();
      return new DriverManagerDataSource(
          url + (url.contains("?") ? "&" : "?") + "socketTimeout=30&connectTimeout=5",
          POSTGRES.getUsername(),
          POSTGRES.getPassword());
    }

    @Bean
    JdbcTemplate jdbc(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    NamedParameterJdbcTemplate named(JdbcTemplate jdbc) {
      return new NamedParameterJdbcTemplate(jdbc);
    }

    @Bean
    PlatformTransactionManager manager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    OutboxStore store(NamedParameterJdbcTemplate named) {
      return new JdbcOutboxStore(named);
    }
  }
}
