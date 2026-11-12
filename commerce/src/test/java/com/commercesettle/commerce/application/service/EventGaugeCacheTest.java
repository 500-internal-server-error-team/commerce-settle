package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.service.SaleEventService.GaugeView;
import com.commercesettle.commerce.domain.exception.SaleEventNotFoundException;
import com.commercesettle.commerce.domain.model.EventAdmission;
import com.commercesettle.commerce.domain.model.SaleEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 게이지 캐시가 TTL 안에서는 DB를 다시 읽지 않고, TTL 뒤·실패 뒤에는 다시 읽으며, 동시 미스는 한 번만 읽는지 확인한다. */
class EventGaugeCacheTest {
  private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final Instant T0 = Instant.parse("2026-11-03T01:00:00Z");

  private final SaleEventService events = mock(SaleEventService.class);
  private final MutableClock clock = new MutableClock(T0);

  @Test
  @DisplayName("1초 안의 재조회는 처음 계산한 값과 asOf를 그대로 돌려주고 DB를 다시 읽지 않는다")
  void servesCachedValueWithinTtl() {
    when(events.gauge(EVENT)).thenAnswer(invocation -> view(clock.instant(), 10));
    EventGaugeCache cache = new EventGaugeCache(events, clock, Duration.ofSeconds(1));

    GaugeView first = cache.gauge(EVENT);
    clock.now.set(T0.plusMillis(999));
    GaugeView second = cache.gauge(EVENT);

    assertThat(second).isSameAs(first);
    assertThat(second.asOf()).isEqualTo(T0);
    verify(events, times(1)).gauge(EVENT);
  }

  @Test
  @DisplayName("TTL이 지나면 다시 읽어 새 값과 asOf를 돌려준다")
  void reloadsAfterTtl() {
    when(events.gauge(EVENT))
        .thenAnswer(invocation -> view(clock.instant(), 10))
        .thenAnswer(invocation -> view(clock.instant(), 20));
    EventGaugeCache cache = new EventGaugeCache(events, clock, Duration.ofSeconds(1));

    cache.gauge(EVENT);
    clock.now.set(T0.plusSeconds(1));
    GaugeView reloaded = cache.gauge(EVENT);

    assertThat(reloaded.gauge().soldCount()).isEqualTo(20);
    assertThat(reloaded.asOf()).isEqualTo(T0.plusSeconds(1));
    verify(events, times(2)).gauge(EVENT);
  }

  @Test
  @DisplayName("조회 실패는 저장하지 않아 다음 요청이 다시 읽는다")
  void doesNotCacheFailures() {
    when(events.gauge(EVENT))
        .thenThrow(new SaleEventNotFoundException(EVENT))
        .thenAnswer(invocation -> view(clock.instant(), 10));
    EventGaugeCache cache = new EventGaugeCache(events, clock, Duration.ofSeconds(1));

    assertThatThrownBy(() -> cache.gauge(EVENT)).isInstanceOf(SaleEventNotFoundException.class);
    assertThat(cache.gauge(EVENT).gauge().soldCount()).isEqualTo(10);
    verify(events, times(2)).gauge(EVENT);
  }

  @Test
  @DisplayName("TTL이 0이면 캐시하지 않는다")
  void bypassesWhenTtlIsZero() {
    when(events.gauge(EVENT)).thenAnswer(invocation -> view(clock.instant(), 10));
    EventGaugeCache cache = new EventGaugeCache(events, clock, Duration.ZERO);

    cache.gauge(EVENT);
    cache.gauge(EVENT);

    verify(events, times(2)).gauge(EVENT);
  }

  @Test
  @DisplayName("같은 이벤트의 동시 미스는 DB를 한 번만 읽고 모두 같은 값을 받는다")
  void loadsOnceForConcurrentMisses() throws Exception {
    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(events.gauge(any()))
        .thenAnswer(
            invocation -> {
              loading.countDown();
              release.await(5, TimeUnit.SECONDS);
              return view(clock.instant(), 10);
            });
    EventGaugeCache cache = new EventGaugeCache(events, clock, Duration.ofSeconds(1));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<GaugeView>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        results.add(pool.submit(() -> cache.gauge(EVENT)));
      }
      assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100);
      release.countDown();
      GaugeView first = results.get(0).get(5, TimeUnit.SECONDS);
      for (Future<GaugeView> result : results) {
        assertThat(result.get(5, TimeUnit.SECONDS)).isSameAs(first);
      }
    } finally {
      pool.shutdownNow();
    }
    verify(events, times(1)).gauge(EVENT);
  }

  private static GaugeView view(Instant asOf, long sold) {
    return new GaugeView(
        EVENT,
        new SaleEvent.Gauge(100, sold, 0, 100 - sold, (int) sold, EventAdmission.OPEN),
        asOf);
  }

  private static final class MutableClock extends Clock {
    final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      now = new AtomicReference<>(start);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
