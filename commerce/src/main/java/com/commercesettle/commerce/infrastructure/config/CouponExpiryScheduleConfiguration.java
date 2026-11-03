package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.service.CouponService;
import com.commercesettle.commerce.infrastructure.scheduling.CouponExpiryScheduler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 쿠폰 만료 스케줄러. 기본으로 켜며 실행 간격·묶음 크기는 설정으로 정한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "app.coupons.expiry-scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CouponExpiryScheduleConfiguration {
  @Bean
  CouponExpiryScheduler couponExpiryScheduler(
      CouponService coupons,
      Clock clock,
      @Value("${app.coupons.expiry-batch-size:100}") int batchSize) {
    return new CouponExpiryScheduler(coupons, clock, batchSize);
  }
}
