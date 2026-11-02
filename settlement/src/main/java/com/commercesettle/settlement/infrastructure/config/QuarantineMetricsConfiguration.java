package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.infrastructure.observability.QuarantineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 격리를 켠 경우에만 격리 원문·발행 작업을 관측한다. Payment 격리 지표와 같은 의미·같은 경보 기준이다. */
@Configuration
@ConditionalOnProperty(name = "app.kafka.quarantine.enabled", havingValue = "true")
public class QuarantineMetricsConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  QuarantineMetrics quarantineMetrics(
      DataSource dataSource,
      MeterRegistry registry,
      @Value("${spring.kafka.consumer.group-id:settlement.v1}") String consumer,
      @Value("${app.kafka.quarantine.metrics.interval-ms:15000}") long intervalMs) {
    return new QuarantineMetrics(dataSource, registry, consumer, intervalMs);
  }
}
