package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.service.QuarantineService;
import com.commercesettle.payment.infrastructure.messaging.QuarantineRelay;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.kafka.quarantine.enabled", havingValue = "true")
public class QuarantineConfiguration {
  @Bean
  QuarantineRelay quarantineRelay(
      QuarantineService service,
      KafkaTemplate<String, byte[]> kafka,
      @Value("${app.kafka.quarantine.batch-size:32}") int batchSize,
      @Value("${app.kafka.quarantine.lease-ms:240000}") long leaseMs,
      @Value("${app.kafka.quarantine.result-timeout-ms:125000}") long timeoutMs,
      @Value("${spring.kafka.producer.properties.max.block.ms:60000}") long maxBlockMs) {
    return new QuarantineRelay(service, kafka, batchSize, leaseMs, timeoutMs, maxBlockMs);
  }
}
