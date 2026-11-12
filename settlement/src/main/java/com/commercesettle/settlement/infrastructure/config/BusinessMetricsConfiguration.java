package com.commercesettle.settlement.infrastructure.config;

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
    // 최초 원장 5 leg는 한 결제확정 이벤트에서 함께 만들어진다(SettlementEntry.initial). 원 이벤트는 SELLER leg 하나로 찾아
    // leg 수만큼 Inbox 조인·정렬 행을 늘리지 않는다. 고르는 Inbox 행과 순서 기준은 5 leg 조인과 같다.
    String facts =
        """
        SELECT DISTINCT ON (s.id) s.created_at AS completed_at, i.order_accepted_at,
            (i.payload->'payload'->>'confirmedAt')::timestamptz AS confirmed_at
        FROM settlement.settlement_items s
        LEFT JOIN settlement.settlement_entries e ON e.item_id=s.id AND e.reversal_of IS NULL
          AND e.leg='SELLER'
        LEFT JOIN settlement.inbox i ON i.event_id=e.source_event_id
          AND i.result_ref=s.id AND i.event_type='PaymentConfirmed' AND i.status='APPLIED'
        WHERE s.created_at >= statement_timestamp() - interval '900 seconds'
          AND s.created_at < statement_timestamp()
        ORDER BY s.id, i.created_at NULLS LAST, e.created_at, e.id, i.id
        """;
    return Map.of(
        "order.settlement.applied",
        "SELECT completed_at, order_accepted_at AS started_at FROM (" + facts + ") facts",
        "payment.settlement.applied",
        "SELECT completed_at, confirmed_at AS started_at FROM (" + facts + ") facts");
  }
}
