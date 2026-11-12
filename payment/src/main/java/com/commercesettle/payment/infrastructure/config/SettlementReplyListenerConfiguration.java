package com.commercesettle.payment.infrastructure.config;

import com.commercesettle.payment.application.port.OrderPaymentRequestAcceptor;
import com.commercesettle.payment.application.service.QuarantineService;
import com.commercesettle.payment.infrastructure.messaging.SettlementReplyListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Settlement 환불 응답 수신. Settlement 토픽과 이 그룹의 offset을 준비한 뒤에만 켠다. */
@Configuration
@ConditionalOnProperty(name = "app.kafka.settlement-replies.enabled", havingValue = "true")
public class SettlementReplyListenerConfiguration {

  @Bean
  SettlementReplyListener settlementReplyListener(
      ObjectMapper objectMapper,
      OrderPaymentRequestAcceptor acceptor,
      ObjectProvider<QuarantineService> quarantine,
      @Value("${app.kafka.quarantine.enabled:false}") boolean enabled,
      @Value("${spring.kafka.consumer.group-id:payment.v1}") String consumer) {
    return new SettlementReplyListener(
        objectMapper, acceptor, enabled ? quarantine.getObject() : null, consumer);
  }
}
