package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.infrastructure.observability.FinancialQueueMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
/**
 * DB 관측 지표는 Payment API·Worker가 같은 DB를 같은 값으로 읽으므로 Worker(역할 미지정 단일 실행 포함)에서만 수집한다. Prometheus 규칙은
 * 최신 표본 하나를 고르므로 수집 역할이 하나여도 값은 같다.
 */
@ConditionalOnExpression("'${PAYMENT_ROLE:app}' != 'api'")
public class FinancialMetricsConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  FinancialQueueMetrics financialQueueMetrics(
      DataSource dataSource, ObjectProvider<PrometheusMeterRegistry> registries) {
    var registry = registries.getIfAvailable();
    return new FinancialQueueMetrics(
        dataSource, registry == null ? null : registry.getPrometheusRegistry());
  }
}
