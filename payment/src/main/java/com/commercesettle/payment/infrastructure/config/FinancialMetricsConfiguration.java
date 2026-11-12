package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.infrastructure.observability.FinancialQueueMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FinancialMetricsConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  FinancialQueueMetrics financialQueueMetrics(
      DataSource dataSource, ObjectProvider<PrometheusMeterRegistry> registries) {
    var registry = registries.getIfAvailable();
    return new FinancialQueueMetrics(
        dataSource, registry == null ? null : registry.getPrometheusRegistry());
  }
}
