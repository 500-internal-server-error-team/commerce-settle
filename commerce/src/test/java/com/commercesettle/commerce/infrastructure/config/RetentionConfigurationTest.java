package com.commercesettle.commerce.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercesettle.commerce.application.service.RetentionService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class RetentionConfigurationTest {
  private final RetentionService service = mock(RetentionService.class);
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(RetentionConfiguration.class)
          .withBean(RetentionService.class, () -> service);

  @Test
  @DisplayName("기본 설정에서는 보존 실행기를 만들지 않는다")
  void remainsDisabledUntilSchemaIsVerified() {
    context.run(app -> assertThat(app).doesNotHaveBean(RetentionConfiguration.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "app.retention.batch-size=0",
        "app.retention.batch-size=1001",
        "app.retention.max-batches=0",
        "app.retention.max-batches=101",
        "app.retention.cron=invalid"
      })
  @DisplayName("범위를 벗어난 실행량이나 잘못된 주기는 활성화를 거절한다")
  void rejectsInvalidLimitsAndCron(String invalid) {
    context
        .withPropertyValues("app.retention.enabled=true", invalid)
        .run(app -> assertThat(app).hasFailed());
  }

  @Test
  @DisplayName("기동 재개는 전용 단일 실행기에 설정한 묶음 한도를 전달한다")
  void resumesOnDedicatedExecutor() {
    context
        .withPropertyValues(
            "app.retention.enabled=true",
            "app.retention.cron=-",
            "app.retention.batch-size=7",
            "app.retention.max-batches=2")
        .run(
            app -> {
              assertThat(app).hasNotFailed();
              var scheduler = app.getBean("retentionScheduler", ThreadPoolTaskScheduler.class);
              assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
              app.getBean(RetentionConfiguration.class).resume();
              scheduler.submit(() -> {}).get(3, TimeUnit.SECONDS);
              verify(service).run(7, 2);
            });
  }
}
