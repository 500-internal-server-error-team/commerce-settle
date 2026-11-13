package com.commercesettle.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.support.JdbcExecutionRecorder;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringJUnitConfig(TransactionLimitsIntegrationTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionLimitsIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.3");

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired JdbcExecutionRecorder recorder;
  @Autowired DataSource source;

  @Test
  @DisplayName("한 물리 트랜잭션의 반복 적용은 설정 SQL을 한 번만 실행하고 REQUIRES_NEW는 다시 설정한다")
  void appliesOncePerPhysicalTransaction() {
    var outer = new TransactionTemplate(manager);
    var inner = new TransactionTemplate(manager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    var sample =
        recorder.measure(
            () ->
                outer.execute(
                    status -> {
                      TransactionLimits.apply(jdbc);
                      TransactionLimits.apply(jdbc);
                      String nested =
                          inner.execute(
                              s -> {
                                TransactionLimits.apply(jdbc);
                                TransactionLimits.apply(jdbc);
                                return jdbc.queryForObject("SHOW lock_timeout", String.class);
                              });
                      TransactionLimits.apply(jdbc);
                      return Map.of(
                          "nested",
                          nested,
                          "outer",
                          jdbc.queryForObject("SHOW lock_timeout", String.class));
                    }));
    String afterCommit =
        outer.execute(
            status -> {
              TransactionLimits.apply(jdbc);
              return jdbc.queryForObject("SHOW statement_timeout", String.class);
            });

    assertThat(sample.timeoutSettings()).isEqualTo(2);
    assertThat(sample.result()).containsEntry("nested", "1s").containsEntry("outer", "1s");
    assertThat(afterCommit).isEqualTo("5s");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("더 짧은 잠금·SQL 제한을 보존하고 초과 시 앞선 쓰기도 롤백하며 연결을 반환한다")
  void shorterLimitsRollBackTheWholeTransaction(boolean lock) throws Exception {
    jdbc.execute("CREATE TABLE IF NOT EXISTS budget_probe (id uuid PRIMARY KEY, value int)");
    UUID held = UUID.randomUUID(), written = UUID.randomUUID();
    jdbc.update("INSERT INTO budget_probe VALUES (?,0)", held);
    try (var holder = source.getConnection()) {
      holder.setAutoCommit(false);
      if (lock) {
        try (var statement =
            holder.prepareStatement("SELECT id FROM budget_probe WHERE id=? FOR UPDATE")) {
          statement.setObject(1, held);
          statement.executeQuery().close();
        }
      }
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.execute("SET LOCAL lock_timeout='100ms'");
                            jdbc.execute("SET LOCAL statement_timeout='500ms'");
                            TransactionLimits.apply(jdbc);
                            assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class))
                                .isEqualTo("100ms");
                            assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class))
                                .isEqualTo("500ms");
                            jdbc.update("INSERT INTO budget_probe VALUES (?,1)", written);
                            if (lock)
                              jdbc.update("UPDATE budget_probe SET value=1 WHERE id=?", held);
                            else jdbc.execute("SELECT pg_sleep(2)");
                          }))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .satisfies(
              error -> {
                Throwable cause = error;
                while (!(cause instanceof java.sql.SQLException) && cause.getCause() != null)
                  cause = cause.getCause();
                assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) cause).getSQLState())
                    .isEqualTo(lock ? "55P03" : "57014");
              });
      holder.rollback();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM budget_probe WHERE id=?", Long.class, written))
        .isZero();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("0");
              assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class))
                  .isEqualTo("0");
            });
    assertThat(source.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections())
        .isZero();
  }

  @Test
  @DisplayName("개별 SQL 제한은 여러 SQL의 누적 트랜잭션 시간을 제한하지 않는다")
  void statementTimeoutIsNotACumulativeTransactionDeadline() {
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL statement_timeout='500ms'");
              TransactionLimits.apply(jdbc);
              var started =
                  jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class);
              for (int i = 0; i < 4; i++) jdbc.execute("SELECT pg_sleep(0.2)");
              var ended =
                  jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class);
              assertThat(java.time.Duration.between(started, ended))
                  .isGreaterThan(java.time.Duration.ofMillis(500));
            });
  }

  @Configuration
  static class Config {
    @Bean
    static JdbcExecutionRecorder recorder() {
      return new JdbcExecutionRecorder();
    }

    @Bean
    HikariDataSource dataSource() {
      var pool = new HikariDataSource();
      pool.setJdbcUrl(POSTGRES.getJdbcUrl());
      pool.setUsername(POSTGRES.getUsername());
      pool.setPassword(POSTGRES.getPassword());
      pool.setMaximumPoolSize(2);
      return pool;
    }

    @Bean
    JdbcTemplate jdbc(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(source);
      factory.setPackagesToScan("com.commercesettle.commerce.infrastructure.persistence.none");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      return factory;
    }

    @Bean
    PlatformTransactionManager manager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
  }
}
