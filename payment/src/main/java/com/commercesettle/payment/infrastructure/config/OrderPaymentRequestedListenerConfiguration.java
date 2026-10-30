package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.service.QuarantineService;
import com.commercesettle.payment.infrastructure.messaging.OrderPaymentRequestedListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.kafka.order-payment-requested.enabled", havingValue = "true")
public class OrderPaymentRequestedListenerConfiguration {

  @Bean
  OrderPaymentRequestedListener orderPaymentRequestedListener(
      ObjectMapper objectMapper,
      OrderPaymentRequestAcceptor acceptor,
      ObjectProvider<QuarantineService> quarantine,
      @Value("${app.kafka.quarantine.enabled:false}") boolean enabled,
      @Value("${spring.kafka.consumer.group-id:payment.v1}") String consumer) {
    return new OrderPaymentRequestedListener(
        objectMapper, acceptor, enabled ? quarantine.getObject() : null, consumer);
  }
}
