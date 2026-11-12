package com.commercesettle.commerce.infrastructure.config;

import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.application.service.NotificationDeliveryService;
import com.commercesettle.commerce.application.service.NotificationService;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoSelfMessageClient;
import com.commercesettle.commerce.infrastructure.client.kakao.KakaoSelfNotificationGateway;
import com.commercesettle.commerce.infrastructure.messaging.NotificationListener;
import com.commercesettle.commerce.infrastructure.mock.MockNotificationGateway;
import com.commercesettle.commerce.infrastructure.observability.NotificationMetrics;
import com.commercesettle.commerce.infrastructure.persistence.notification.NotificationReceiptStore;
import com.commercesettle.observability.RepeatedWarning;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {
  @Bean
  NotificationGateway notificationGateway(
      NotificationReceiptStore receipts,
      Clock clock,
      ObjectProvider<KakaoSelfMessageClient> self,
      ObjectProvider<KakaoSelfProperties> properties,
      @Value("${app.notifications.mock.seed:67}") long seed,
      @Value("${app.notifications.mock.delay-ms:0}") long delay,
      @Value("${app.notifications.mock.temporary-failure-permille:0}") int temporary,
      @Value("${app.notifications.mock.permanent-failure-permille:0}") int permanent,
      @Value("${app.notifications.mock.response-loss-permille:0}") int loss) {
    var mock =
        new MockNotificationGateway(receipts, clock, seed, delay, temporary, permanent, loss);
    var client = self.getIfAvailable();
    return client == null
        ? mock
        : new KakaoSelfNotificationGateway(mock, client, properties.getObject(), clock);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "app.notifications.metrics.enabled", havingValue = "true")
  NotificationMetrics notificationMetrics(DataSource dataSource, MeterRegistry registry) {
    return new NotificationMetrics(dataSource, registry);
  }

  @Bean(defaultCandidate = false, destroyMethod = "shutdownNow")
  ExecutorService notificationCalls() {
    return new ThreadPoolExecutor(
        2,
        2,
        0,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8),
        Thread.ofPlatform().name("notification-call-", 0).daemon(true).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  NotificationDeliveryService notificationDeliveryService(
      NotificationStore store,
      NotificationGateway gateway,
      @Qualifier("notificationCalls") ExecutorService calls,
      Clock clock,
      PlatformTransactionManager manager) {
    return new NotificationDeliveryService(store, gateway, calls, clock, manager);
  }

  @Bean
  @ConditionalOnProperty(name = "app.notifications.consumer-enabled", havingValue = "true")
  NotificationListener notificationListener(ObjectMapper mapper, NotificationService service) {
    return new NotificationListener(mapper, service);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "app.notifications.worker-enabled", havingValue = "true")
  NotificationPump notificationPump(
      NotificationService notices, NotificationDeliveryService deliveries) {
    return new NotificationPump(notices, deliveries);
  }

  static final class NotificationPump implements AutoCloseable {
    private final ScheduledExecutorService executor =
        Executors.newScheduledThreadPool(
            2, Thread.ofPlatform().name("notification-pump-", 0).daemon(true).factory());
    private final ExecutorService workers =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),
            Thread.ofPlatform().name("notification-worker-", 0).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    private final RepeatedWarning noticePollFailed = new RepeatedWarning();
    private final RepeatedWarning deliveryPollFailed = new RepeatedWarning();

    NotificationPump(NotificationService notices, NotificationDeliveryService deliveries) {
      executor.scheduleWithFixedDelay(
          () -> {
            try {
              for (int index = 0; index < 100 && notices.processNextNotice(); index++) {}
            } catch (RuntimeException e) {
              noticePollFailed
                  .at(org.slf4j.LoggerFactory.getLogger(NotificationPump.class))
                  .addKeyValue("event", "notification.notice.poll")
                  .addKeyValue("result", "UNKNOWN")
                  .addKeyValue("errorCode", "NOTIFICATION_NOTICE_POLL_FAILED")
                  .log("Notification processing deferred: {}", e.getClass().getSimpleName());
            }
          },
          0,
          1,
          TimeUnit.SECONDS);
      executor.scheduleWithFixedDelay(
          () -> {
            int available = 2 - inFlight.get();
            for (int index = 0; index < available; index++) {
              inFlight.incrementAndGet();
              try {
                workers.execute(
                    () -> {
                      try {
                        deliveries.processNext();
                      } catch (RuntimeException e) {
                        deliveryPollFailed
                            .at(org.slf4j.LoggerFactory.getLogger(NotificationPump.class))
                            .addKeyValue("event", "notification.delivery.poll")
                            .addKeyValue("result", "UNKNOWN")
                            .addKeyValue("errorCode", "NOTIFICATION_DELIVERY_POLL_FAILED")
                            .log(
                                "Notification delivery deferred: {}", e.getClass().getSimpleName());
                      } finally {
                        inFlight.decrementAndGet();
                      }
                    });
              } catch (java.util.concurrent.RejectedExecutionException e) {
                inFlight.decrementAndGet();
                break;
              }
            }
          },
          0,
          1,
          TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      executor.shutdownNow();
      workers.shutdownNow();
    }
  }
}
