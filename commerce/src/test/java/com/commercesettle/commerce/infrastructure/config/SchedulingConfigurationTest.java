package com.commercesettle.commerce.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConfigurationTest {
  private ApplicationContextRunner runner() throws IOException {
    // 실제 commerce 설정의 스레드 수로 확인한다.
    var poolSize =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))
            .getFirst()
            .getProperty("spring.task.scheduling.pool.size");
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(SchedulingConfiguration.class, LongAndShortTasks.class)
        .withPropertyValues("spring.task.scheduling.pool.size=" + poolSize);
  }

  @Test
  @DisplayName("Inbox 처리기가 스레드를 오래 쓰는 동안에도 다른 예약 작업이 실행된다")
  void otherScheduledWorkRunsWhileOneTaskHoldsAThread() throws IOException {
    runner().run(SchedulingConfigurationTest::assertOtherTaskRuns);
  }

  @Test
  @DisplayName("보존 전용 단일 스레드 스케줄러가 함께 있어도 다른 예약 작업은 기본 스케줄러로 실행된다")
  void retentionSchedulerDoesNotBecomeTheDefault() throws IOException {
    runner()
        .withUserConfiguration(RetentionLikeScheduler.class)
        .run(
            context -> {
              assertThat(
                      context
                          .getBean("taskScheduler", ThreadPoolTaskScheduler.class)
                          .getScheduledThreadPoolExecutor()
                          .getCorePoolSize())
                  .isGreaterThan(2);
              assertOtherTaskRuns(context);
            });
  }

  private static void assertOtherTaskRuns(AssertableApplicationContext context)
      throws InterruptedException {
    var tasks = context.getBean(Tasks.class);
    try {
      assertThat(tasks.holding.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(tasks.otherRan.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      tasks.release.countDown();
    }
  }

  @Configuration
  @EnableScheduling
  static class LongAndShortTasks {
    @Bean
    Tasks tasks() {
      return new Tasks();
    }
  }

  /** 한 작업이 연속 처리처럼 스레드를 붙잡는 동안 다른 작업이 실행되는지 본다. */
  static class Tasks {
    final CountDownLatch holding = new CountDownLatch(1);
    final CountDownLatch otherRan = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Scheduled(fixedDelay = 100)
    void holdsAThread() throws InterruptedException {
      holding.countDown();
      release.await(10, TimeUnit.SECONDS);
    }

    @Scheduled(fixedDelay = 100, initialDelay = 200)
    void other() {
      otherRan.countDown();
    }
  }

  @Configuration
  static class RetentionLikeScheduler {
    @Bean(name = "retentionScheduler")
    ThreadPoolTaskScheduler retentionScheduler() {
      var scheduler = new ThreadPoolTaskScheduler();
      scheduler.setPoolSize(1);
      return scheduler;
    }
  }
}
