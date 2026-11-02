package com.commercesettle.settlement.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration
public class KafkaListenerConfiguration {

  @Bean
  CommonErrorHandler kafkaErrorHandler() {
    // 격리하지 않는 실패(반영 중 DB 오류, 격리 저장 실패, 격리 꺼짐)는 레코드를 건너뛰지 않는다.
    return new CommonContainerStoppingErrorHandler();
  }
}
