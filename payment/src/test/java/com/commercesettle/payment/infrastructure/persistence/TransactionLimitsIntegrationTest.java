package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Timeout(60)
class TransactionLimitsIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.3");

  private final AtomicInteger applied = new AtomicInteger();
  private CountingJdbcTemplate jdbc;
  private TransactionTemplate outer;
  private TransactionTemplate inner;

  @BeforeEach
  void setUp() {
    DataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new CountingJdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    outer = new TransactionTemplate(manager);
    inner = new TransactionTemplate(manager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  @DisplayName("같은 트랜잭션에서는 대기 상한을 한 번만 걸고 다음 트랜잭션에 다시 건다")
  void appliesOncePerTransaction() {
    String timeout =
        outer.execute(
            status -> {
              TransactionLimits.apply(jdbc);
              TransactionLimits.apply(jdbc);
              TransactionLimits.apply(jdbc);
              return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
            });
    outer.executeWithoutResult(status -> TransactionLimits.apply(jdbc));

    assertThat(timeout).isEqualTo("1s");
    assertThat(applied).hasValue(2);
  }

  @Test
  @DisplayName("REQUIRES_NEW 트랜잭션에도 대기 상한을 건다")
  void appliesToRequiresNewTransaction() {
    String innerTimeout =
        outer.execute(
            status -> {
              TransactionLimits.apply(jdbc);
              return inner.execute(
                  nested -> {
                    TransactionLimits.apply(jdbc);
                    return jdbc.queryForObject(
                        "SELECT current_setting('statement_timeout')", String.class);
                  });
            });

    assertThat(innerTimeout).isEqualTo("5s");
    assertThat(applied).hasValue(2);
  }

  @Test
  @DisplayName("호출자가 먼저 건 더 짧은 상한은 유지한다")
  void keepsShorterCallerLimit() {
    String timeout =
        outer.execute(
            status -> {
              jdbc.execute("SET LOCAL lock_timeout = '200ms'");
              TransactionLimits.apply(jdbc);
              return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
            });

    assertThat(timeout).isEqualTo("200ms");
  }

  private final class CountingJdbcTemplate extends JdbcTemplate {
    CountingJdbcTemplate(DataSource source) {
      super(source);
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql) {
      if (sql.contains("set_config")) {
        applied.incrementAndGet();
      }
      return super.queryForList(sql);
    }
  }
}
