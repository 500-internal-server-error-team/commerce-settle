package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.infrastructure.persistence.DevFixtureLoader;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** API 역할 하나가 초기 데이터를 소유한다. Worker 기동은 fixture를 다시 실행하지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.dev-fixtures.enabled", havingValue = "true")
public class DevFixtureConfiguration {
  @Bean
  @ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "api", matchIfMissing = true)
  NewTopic devFixturePaymentTopic() {
    return TopicBuilder.name(DevFixtureLoader.TOPIC).partitions(1).replicas(1).build();
  }

  @Bean
  @ConditionalOnProperty(name = "PAYMENT_ROLE", havingValue = "api", matchIfMissing = true)
  ApplicationRunner paymentDevFixtures(
      DevFixtureLoader loader,
      @Value("${app.dev-fixtures.run-scenarios:false}") boolean runScenarios) {
    return args -> {
      loader.load();
      if (runScenarios) loader.runScenarios();
    };
  }
}
