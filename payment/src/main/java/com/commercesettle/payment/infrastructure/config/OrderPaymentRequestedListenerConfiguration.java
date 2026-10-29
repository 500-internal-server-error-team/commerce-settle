package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.infrastructure.messaging.OrderPaymentRequestedListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.kafka.order-payment-requested.enabled", havingValue = "true")
public class OrderPaymentRequestedListenerConfiguration {

  @Bean
  OrderPaymentRequestedListener orderPaymentRequestedListener(
      ObjectMapper objectMapper, OrderPaymentRequestAcceptor acceptor) {
    return new OrderPaymentRequestedListener(objectMapper, acceptor);
  }
}
