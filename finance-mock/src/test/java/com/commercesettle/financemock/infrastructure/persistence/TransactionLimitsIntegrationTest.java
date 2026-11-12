package com.commercesettle.financemock.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "management.server.port=0",
      "management.tracing.enabled=false",
      "app.internal-auth.senders[service\\:settlement].current="
          + "test-settlement-credential-0123456789abcdef0123",
      "app.internal-auth.senders[service\\:payment].current="
          + "test-payment-credential-0123456789abcdef0123456"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionLimitsIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/finance_mock.sql");

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  private String lockTimeout() {
    return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
  }

  @Test
  @DisplayName("같은 트랜잭션의 반복 호출은 상한을 다시 설정하지 않고, 새 트랜잭션은 다시 적용한다")
  void appliesOncePerPhysicalTransaction() {
    var outer = new TransactionTemplate(transactions);
    var inner = new TransactionTemplate(transactions);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    outer.executeWithoutResult(
        status -> {
          TransactionLimits.apply(jdbc);
          assertThat(lockTimeout()).isEqualTo("1s");
          // 이미 적용한 트랜잭션에서는 두 번째 호출이 SQL을 보내지 않으므로 바꾼 값이 남는다.
          jdbc.queryForList("SELECT set_config('lock_timeout','3000',true)");
          TransactionLimits.apply(jdbc);
          assertThat(lockTimeout()).isEqualTo("3s");
          inner.executeWithoutResult(
              nested -> {
                TransactionLimits.apply(jdbc);
                assertThat(lockTimeout()).isEqualTo("1s");
              });
          assertThat(lockTimeout()).isEqualTo("3s");
        });
    outer.executeWithoutResult(
        status -> {
          TransactionLimits.apply(jdbc);
          assertThat(lockTimeout()).isEqualTo("1s");
        });
  }
}
