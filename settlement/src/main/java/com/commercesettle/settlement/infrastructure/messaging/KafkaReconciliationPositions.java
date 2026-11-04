package com.commercesettle.settlement.infrastructure.messaging;

import com.commercesettle.settlement.application.port.ReconciliationPositions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component
public class KafkaReconciliationPositions implements ReconciliationPositions {
  private final KafkaAdmin configuration;
  private final String consumer;

  public KafkaReconciliationPositions(
      KafkaAdmin configuration,
      @Value("${spring.kafka.consumer.group-id:settlement.v1}") String consumer) {
    this.configuration = configuration;
    this.consumer = consumer;
  }

  @Override
  public Map<String, Long> committed() {
    try (Admin admin = Admin.create(configuration.getConfigurationProperties())) {
      Map<String, Long> positions = new HashMap<>();
      admin
          .listConsumerGroupOffsets(consumer)
          .partitionsToOffsetAndMetadata()
          .get(5, TimeUnit.SECONDS)
          .forEach(
              (p, o) -> {
                if (o != null) positions.put(p.topic() + ":" + p.partition(), o.offset());
              });
      return Map.copyOf(positions);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("Own consumer position unavailable", e);
    }
  }
}
