package com.commercesettle.commerce.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.commerce.support.JdbcExecutionRecorder;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
