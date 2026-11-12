package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.infrastructure.observability.FinancialQueueMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FinancialMetricsConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  FinancialQueueMetrics financialQueueMetrics(
      DataSource dataSource,
      ObjectProvider<PrometheusMeterRegistry> registries,
      @Value("${spring.kafka.consumer.group-id:settlement.v1}") String consumer) {
    var registry = registries.getIfAvailable();
    return new FinancialQueueMetrics(
        dataSource, registry == null ? null : registry.getPrometheusRegistry(), consumer);
  }
}
