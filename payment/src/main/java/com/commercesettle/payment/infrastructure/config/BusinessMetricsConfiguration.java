package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.observability.infrastructure.BusinessMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
/**
 * DB 관측 지표는 Payment API·Worker가 같은 DB를 같은 값으로 읽으므로 Worker(역할 미지정 단일 실행 포함)에서만 수집한다. Prometheus 규칙은
 * 최신 표본 하나를 고르므로 수집 역할이 하나여도 값은 같다.
 */
@ConditionalOnExpression("'${PAYMENT_ROLE:app}' != 'api'")
@ConditionalOnProperty(name = "app.business-metrics.enabled", matchIfMissing = true)
public class BusinessMetricsConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  BusinessMetrics businessMetrics(
      DataSource dataSource, ObjectProvider<PrometheusMeterRegistry> registries) {
    var registry = registries.getIfAvailable();
    return new BusinessMetrics(
        dataSource, registry == null ? null : registry.getPrometheusRegistry(), sources());
  }

  public static Map<String, String> sources() {
    var sources = new HashMap<>(BusinessMetrics.deliverySources("payment"));
    sources.putAll(
        Map.of(
            "payment.confirmed",
                """
          SELECT confirmed_at AS completed_at, created_at AS started_at FROM payment.payments
          WHERE confirmed_at >= statement_timestamp() - interval '900 seconds'
            AND confirmed_at < statement_timestamp()
          """,
            "refund.completed",
                """
          SELECT s.order_restored_at AS completed_at, r.created_at AS started_at
          FROM payment.refund_sagas s JOIN payment.refunds r ON r.id=s.refund_id
          WHERE s.step='COMPLETED' AND r.status='COMPLETED'
            AND s.order_restored_at >= statement_timestamp() - interval '900 seconds'
            AND s.order_restored_at < statement_timestamp()
          """));
    return Map.copyOf(sources);
  }
}
