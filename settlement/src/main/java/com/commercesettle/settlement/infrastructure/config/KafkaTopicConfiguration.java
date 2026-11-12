package com.commercesettle.settlement.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfiguration {

  @Bean
  NewTopic settlementEventsTopic() {
    return topic("commerce-settle.settlement.v1", "86400000");
  }

  @Bean
  NewTopic settlementDeadLetterTopic() {
    return topic("commerce-settle.settlement.v1.dlt", "604800000");
  }

  private static NewTopic topic(String name, String retentionMs) {
    return TopicBuilder.name(name)
        .partitions(3)
        .replicas(1)
        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
        .config(TopicConfig.RETENTION_MS_CONFIG, retentionMs)
        .config(TopicConfig.RETENTION_BYTES_CONFIG, "-1")
        .build();
  }
}
