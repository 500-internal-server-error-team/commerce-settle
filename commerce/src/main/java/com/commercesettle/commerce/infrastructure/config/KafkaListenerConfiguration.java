package com.commercesettle.commerce.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration
public class KafkaListenerConfiguration {

  @Bean
  CommonErrorHandler kafkaErrorHandler() {
    // 영속 인계·격리 구현 전에는 실패 레코드를 건너뛰지 않는다.
    return new CommonContainerStoppingErrorHandler();
  }
}
