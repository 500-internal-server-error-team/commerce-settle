package com.commercesettle.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 트랜잭션 상한을 물리 트랜잭션마다 한 번 걸고, 더 짧은 상한을 올리지 않으며, REQUIRES_NEW에도 다시 거는지 확인한다. */
@Testcontainers
class TransactionLimitsIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.3");

  @Test
  void appliesOncePerPhysicalTransactionAndAgainForRequiresNew() {
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    var outer = new TransactionTemplate(manager);
    var inner = new TransactionTemplate(manager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    outer.executeWithoutResult(
        tx -> {
          jdbc.execute("SET LOCAL lock_timeout = '300ms'");
          TransactionLimits.apply(jdbc);
          assertThat(setting(jdbc, "lock_timeout")).isEqualTo("300ms");
          assertThat(setting(jdbc, "statement_timeout")).isEqualTo("5s");
          // 두 번째 호출은 SQL을 실행하지 않는다. 일부러 올린 값이 그대로 남는 것으로 확인한다.
          jdbc.execute("SET LOCAL statement_timeout = '60s'");
          TransactionLimits.apply(jdbc);
          assertThat(setting(jdbc, "statement_timeout")).isEqualTo("1min");

          inner.executeWithoutResult(
              nested -> {
                assertThat(setting(jdbc, "statement_timeout")).isEqualTo("0");
                TransactionLimits.apply(jdbc);
                assertThat(setting(jdbc, "lock_timeout")).isEqualTo("1s");
                assertThat(setting(jdbc, "statement_timeout")).isEqualTo("5s");
              });
          assertThat(setting(jdbc, "statement_timeout")).isEqualTo("1min");
          TransactionLimits.apply(jdbc);
          assertThat(setting(jdbc, "statement_timeout")).isEqualTo("1min");
        });

    outer.executeWithoutResult(
        tx -> {
          TransactionLimits.apply(jdbc);
          assertThat(setting(jdbc, "lock_timeout")).isEqualTo("1s");
          assertThat(setting(jdbc, "statement_timeout")).isEqualTo("5s");
        });
  }

  @Test
  void appliesEveryTimeWithoutTransaction() {
    var source =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("SET statement_timeout = '60s'");
    TransactionLimits.apply(jdbc);
    // 자동 커밋 문장의 지역 설정은 문장 뒤 사라지므로 세션 값이 바뀌지 않는다.
    assertThat(setting(jdbc, "statement_timeout")).isEqualTo("1min");
    source.destroy();
  }

  private static String setting(JdbcTemplate jdbc, String name) {
    return jdbc.queryForObject("SELECT current_setting(?)", String.class, name);
  }
}
