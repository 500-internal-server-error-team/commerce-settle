package com.commercesettle.commerce.infrastructure.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 작업이 쓰는 기본 스케줄러. 보존 작업 전용 스케줄러가 함께 등록되면 Boot 기본 스케줄러가 만들어지지 않아 다른 작업도 그 단일 스레드를
 * 쓰게 되므로, {@code spring.task.scheduling.*} 설정으로 이름을 고정해 등록한다.
 */
@Configuration
public class SchedulingConfiguration {
  @Bean(name = "taskScheduler")
  ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.build();
  }
}
