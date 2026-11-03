package com.commercesettle.payment.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration
public class KafkaListenerConfiguration {

  @Bean
  CommonErrorHandler kafkaErrorHandler() {
    // 영속 인계·격리 실패 레코드는 건너뛰지 않고 수신을 중단한다.
    return new CommonContainerStoppingErrorHandler();
  }
}
