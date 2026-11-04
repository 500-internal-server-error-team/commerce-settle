package com.commercesettle.settlement.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/** 전용 Kafka 시험의 수신 토픽. 실제 서비스에서는 생산자 서비스가 생성한다. */
@TestConfiguration(proxyBeanMethods = false)
public class SettlementEventTopics {
  @Bean
  NewTopic testPaymentEvents() {
    return TopicBuilder.name("commerce-settle.payment.v1").partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic testCommerceEvents() {
    return TopicBuilder.name("commerce-settle.commerce.v1").partitions(3).replicas(1).build();
  }
}
