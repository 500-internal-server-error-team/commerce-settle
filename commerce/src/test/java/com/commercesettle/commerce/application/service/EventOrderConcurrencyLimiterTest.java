package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.application.exception.OrderCapacityExceededException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventOrderConcurrencyLimiterTest {
  private final UUID event = UUID.randomUUID();

  @Test
  @DisplayName("이벤트별 상한만큼 동시에 받고, 초과 요청은 대기 상한 뒤 Retry-After 1초의 용량 초과로 거절한다")
  void rejectsBeyondLimitAfterWait() {
    var limiter = new EventOrderConcurrencyLimiter(2, Duration.ofMillis(50));
    var first = limiter.acquire(event);
    var second = limiter.acquire(event);

    long started = System.nanoTime();
    assertThatThrownBy(() -> limiter.acquire(event))
        .isInstanceOfSatisfying(
            OrderCapacityExceededException.class,
            e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isGreaterThanOrEqualTo(Duration.ofMillis(50));

    first.close();
    second.close();
  }

  @Test
  @DisplayName("반납한 슬롯은 다시 받을 수 있고, 같은 슬롯을 여러 번 닫아도 상한이 늘지 않는다")
  void releasesOnce() {
    var limiter = new EventOrderConcurrencyLimiter(1, Duration.ZERO);
    var permit = limiter.acquire(event);
    permit.close();
    permit.close();

    var again = limiter.acquire(event);
    assertThatThrownBy(() -> limiter.acquire(event))
        .isInstanceOf(OrderCapacityExceededException.class);
    again.close();
  }

  @Test
  @DisplayName("다른 이벤트의 주문은 서로의 상한에 영향을 주지 않는다")
  void isolatesEvents() {
    var limiter = new EventOrderConcurrencyLimiter(1, Duration.ZERO);
    try (var held = limiter.acquire(event);
        var other = limiter.acquire(UUID.randomUUID())) {
      assertThat(other).isNotNull();
    }
  }

  @Test
  @DisplayName("대기 중 슬롯이 반납되면 대기 상한 안에서 받는다")
  void acquiresWhenReleasedWithinWait() throws Exception {
    var limiter = new EventOrderConcurrencyLimiter(1, Duration.ofSeconds(5));
    var held = limiter.acquire(event);
    var waiting = new CountDownLatch(1);
    var acquired =
        CompletableFuture.supplyAsync(
            () -> {
              waiting.countDown();
              return limiter.acquire(event);
            });
    assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();

    held.close();

    acquired.get(5, TimeUnit.SECONDS).close();
  }

  @Test
  @DisplayName("인터럽트된 대기는 기다리지 않고 거절하며 인터럽트 상태를 보존하고, Retry-After는 대기 상한을 올림한 초다")
  void rejectsInterruptedWaitAndRoundsRetryAfterUp() {
    var limiter = new EventOrderConcurrencyLimiter(1, Duration.ofMillis(1_500));
    var held = limiter.acquire(event);
    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> limiter.acquire(event))
        .isInstanceOfSatisfying(
            OrderCapacityExceededException.class,
            e -> assertThat(e.retryAfterSeconds()).isEqualTo(2));
    assertThat(Thread.interrupted()).isTrue();
    held.close();
  }

  @Test
  @DisplayName("상한은 1 이상이고 대기 상한은 음수일 수 없다")
  void validatesSettings() {
    assertThatThrownBy(() -> new EventOrderConcurrencyLimiter(0, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EventOrderConcurrencyLimiter(1, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
