package com.commercesettle.settlement.infrastructure.config;

import com.commercesettle.settlement.application.service.QuarantineService;
import com.commercesettle.settlement.application.service.SettlementEventService;
import com.commercesettle.settlement.infrastructure.messaging.SettlementEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 신규 그룹 offset 초기화 전에는 켜지 않는다(auto.offset.reset=none). Payment 수신 경계와 같은 방식이다. */
@Configuration
@ConditionalOnProperty(name = "app.kafka.settlement-events.enabled", havingValue = "true")
public class SettlementEventListenerConfiguration {

  @Bean
  SettlementEventListener settlementEventListener(
      ObjectMapper objectMapper,
      SettlementEventService service,
      QuarantineService quarantine,
      @Value("${app.kafka.quarantine.enabled}") boolean quarantineEnabled,
      @Value("${spring.kafka.consumer.group-id:settlement.v1}") String consumer) {
    return new SettlementEventListener(
        objectMapper, service, quarantineEnabled ? quarantine : null, consumer);
  }
}
