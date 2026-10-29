package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.service.OutboxDeliveryService;
import com.commercesettle.commerce.infrastructure.messaging.OutboxRelay;
import java.util.concurrent.Executor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true")
public class OutboxConfiguration {
  @Bean(defaultCandidate = false)
  ThreadPoolTaskExecutor outboxExecutor(@Value("${app.outbox.max-in-flight:8}") int maxInFlight) {
    if (maxInFlight < 1) {
      throw new IllegalArgumentException("Invalid outbox executor limit");
    }
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(maxInFlight);
    executor.setMaxPoolSize(maxInFlight);
    executor.setQueueCapacity(maxInFlight);
    executor.setThreadNamePrefix("outbox-send-");
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }

  @Bean(defaultCandidate = false)
  ThreadPoolTaskExecutor outboxCoordinator() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("outbox-relay-");
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }

  @Bean
  OutboxRelay outboxRelay(
      OutboxDeliveryService service,
      KafkaTemplate<String, byte[]> template,
      KafkaProperties properties,
      @Qualifier("outboxExecutor") Executor executor,
      @Qualifier("outboxCoordinator") Executor coordinator,
      @Value("${app.outbox.max-in-flight:8}") int maxInFlight,
      @Value("${app.outbox.lease-ms:240000}") long leaseMs,
      @Value("${app.outbox.max-attempts:10}") int maxAttempts,
      @Value("${app.outbox.recovery-interval-ms:1000}") long recoveryIntervalMs) {
    var producer = properties.buildProducerProperties();
    long maxBlock =
        Long.parseLong(producer.getOrDefault(ProducerConfig.MAX_BLOCK_MS_CONFIG, 60000).toString());
    long delivery =
        Long.parseLong(
            producer.getOrDefault(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000).toString());
    if (recoveryIntervalMs <= 0
        || maxBlock < 0
        || delivery <= 0
        || leaseMs <= Math.addExact(maxBlock, delivery)) {
      throw new IllegalArgumentException(
          "Outbox lease must exceed producer metadata wait and delivery timeout");
    }
    return new OutboxRelay(
        service, template, maxInFlight, leaseMs, maxAttempts, coordinator, executor);
  }
}
