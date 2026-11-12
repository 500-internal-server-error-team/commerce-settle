package com.commercesettle.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 초기 DDL로 만든 DB에서 결제확정마다 실행하는 구매확정 되살리기 UPDATE가 부분 표현식 인덱스를 쓰는지 확인한다. */
@Testcontainers
class InboxPurchaseRevivalIndexIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/settlement.sql");

  private final JdbcTemplate jdbc =
      new JdbcTemplate(
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

  @Test
  void revivalUpdateUsesThePartialPaymentIdIndex() {
    assertThat(plan()).contains("inbox_purchase_revival");
  }

  private String plan() {
    String sql =
        JdbcInboxStore.REVIVE_EXHAUSTED_PURCHASES
            .replace(":consumer", "$1")
            .replace(":paymentId", "$2")
            .replace(":max", "$3")
            .replace(":now", "$4");
    List<String> lines =
        jdbc.execute(
            (java.sql.Connection connection) -> {
              try (var statement = connection.createStatement()) {
                // 빈 표에서도 인덱스 사용 가능 여부만 보도록 순차 조회를 끈다(같은 연결의 세션 설정).
                statement.execute("SET enable_seqscan = off");
                statement.execute("SET plan_cache_mode = force_generic_plan");
                statement.execute("PREPARE revive(varchar, uuid, integer, timestamptz) AS " + sql);
                var rows =
                    statement.executeQuery(
                        "EXPLAIN EXECUTE revive('settlement.v1', gen_random_uuid(), 10, now())");
                var out = new java.util.ArrayList<String>();
                while (rows.next()) out.add(rows.getString(1));
                return out;
              }
            });
    return String.join("\n", lines);
  }
}
