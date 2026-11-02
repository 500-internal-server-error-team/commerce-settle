package com.commercesettle.commerce.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.service.NotificationDeliveryService;
import com.commercesettle.commerce.application.service.NotificationService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationConfigurationTest {
  @Test
  @DisplayName("선행 대기가 느려도 발송을 예약하고 동시에 두 작업을 넘겨 선점하지 않는다")
  void isolatesNoticePollAndBoundsDeliveryDispatch() throws Exception {
    var notices = mock(NotificationService.class);
    var deliveries = mock(NotificationDeliveryService.class);
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(2);
    var third = new CountDownLatch(3);
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    when(notices.processNextNotice())
        .thenAnswer(
            call -> {
              waitForRelease(release);
              return false;
            });
    when(deliveries.processNext())
        .thenAnswer(
            call -> {
              int count = active.incrementAndGet();
              maximum.accumulateAndGet(count, Math::max);
              started.countDown();
              third.countDown();
              try {
                waitForRelease(release);
                return false;
              } finally {
                active.decrementAndGet();
              }
            });
    try (var pump = new NotificationConfiguration.NotificationPump(notices, deliveries)) {
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(third.await(1500, TimeUnit.MILLISECONDS)).isFalse();
      assertThat(maximum.get()).isEqualTo(2);
    } finally {
      release.countDown();
    }
  }

  private static void waitForRelease(CountDownLatch release) {
    try {
      release.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
