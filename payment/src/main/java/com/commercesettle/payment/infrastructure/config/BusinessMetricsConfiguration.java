package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.observability.infrastructure.BusinessMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
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
    return Map.of(
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
          """);
  }
}
