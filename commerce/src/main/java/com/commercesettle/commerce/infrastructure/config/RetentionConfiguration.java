package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.service.RetentionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true")
public class RetentionConfiguration {
  private final RetentionService service;
  private final int batchSize;
  private final int maxBatches;

  public RetentionConfiguration(
      RetentionService service,
      @Value("${app.retention.batch-size:100}") int batchSize,
      @Value("${app.retention.max-batches:10}") int maxBatches) {
    if (batchSize < 1 || batchSize > 1000 || maxBatches < 1 || maxBatches > 100)
      throw new IllegalArgumentException("Invalid retention limits");
    this.service = service;
    this.batchSize = batchSize;
    this.maxBatches = maxBatches;
  }

  @Bean(name = "retentionScheduler")
  ThreadPoolTaskScheduler retentionScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("retention-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(30);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return scheduler;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void resume() {
    retentionScheduler().execute(this::run);
  }

  @Scheduled(
      cron = "${app.retention.cron:0 0 3 * * *}",
      zone = "Asia/Seoul",
      scheduler = "retentionScheduler")
  public void run() {
    service.run(batchSize, maxBatches);
  }
}
