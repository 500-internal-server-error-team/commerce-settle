package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.service.SaleEventService;
import com.commercesettle.commerce.infrastructure.scheduling.SaleEventScheduler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 이벤트 시작·종료 스케줄러. 기본으로 켜며 실행 간격·묶음 크기는 설정으로 정한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "app.sale-events.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SaleEventScheduleConfiguration {
  @Bean
  SaleEventScheduler saleEventScheduler(
      SaleEventService events,
      Clock clock,
      @Value("${app.sale-events.batch-size:100}") int batchSize) {
    return new SaleEventScheduler(events, clock, batchSize);
  }
}
