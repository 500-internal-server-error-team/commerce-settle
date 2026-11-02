package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.infrastructure.batch.GradeCouponLauncher;
import com.commercesettle.commerce.infrastructure.scheduling.GradeCouponScheduler;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 월간 우수고객 스케줄러. 기본으로 켠다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "app.coupons.grade-scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GradeCouponScheduleConfiguration {
  @Bean
  GradeCouponScheduler gradeCouponScheduler(GradeCouponLauncher launcher, Clock clock) {
    return new GradeCouponScheduler(launcher, clock);
  }
}
