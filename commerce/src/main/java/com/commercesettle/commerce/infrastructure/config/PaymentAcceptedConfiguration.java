package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import com.commercesettle.commerce.infrastructure.messaging.PaymentAcceptedListener;
import com.commercesettle.commerce.infrastructure.messaging.PaymentAcceptedProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class PaymentAcceptedConfiguration {
  @Bean
  @ConditionalOnProperty(name = "app.kafka.payment-accepted.enabled", havingValue = "true")
  PaymentAcceptedListener paymentAcceptedListener(
      ObjectMapper mapper, PaymentAcceptedService service) {
    return new PaymentAcceptedListener(mapper, service);
  }

  @Bean
  @ConditionalOnProperty(name = "app.payment-accepted-processor.enabled", havingValue = "true")
  PaymentAcceptedProcessor paymentAcceptedProcessor(
      PaymentAcceptedService service,
      @Value("${app.payment-accepted-processor.batch-size:100}") int batchSize) {
    return new PaymentAcceptedProcessor(service, batchSize);
  }
}
