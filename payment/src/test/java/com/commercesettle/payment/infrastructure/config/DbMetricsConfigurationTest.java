package com.commercesettle.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.observability.infrastructure.BusinessMetrics;
import com.commercesettle.payment.infrastructure.observability.FinancialQueueMetrics;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DbMetricsConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(
              BusinessMetricsConfiguration.class, FinancialMetricsConfiguration.class)
          .withBean(DataSource.class, DriverManagerDataSource::new);

  @ParameterizedTest
  @CsvSource({"api,false", "worker,true", "app,true"})
  @DisplayName("DB 관측 지표는 API를 뺀 한 역할에서만 수집한다")
  void collectsDbMetricsOutsideApiRole(String role, boolean collected) {
    context
        .withPropertyValues("PAYMENT_ROLE=" + role)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBeansOfType(BusinessMetrics.class)).hasSize(collected ? 1 : 0);
              assertThat(c.getBeansOfType(FinancialQueueMetrics.class)).hasSize(collected ? 1 : 0);
            });
  }
}
