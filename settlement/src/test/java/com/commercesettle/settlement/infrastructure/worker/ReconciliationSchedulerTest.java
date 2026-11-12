package com.commercesettle.settlement.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.port.ReconciliationStore;
import com.commercesettle.settlement.application.service.ReconciliationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconciliationSchedulerTest {
  private static final Instant TARGET = Instant.parse("2026-11-12T01:00:00Z");

  @Test
  @DisplayName("당일 마감을 요청한 뒤에는 다음 마감 전까지 회차 조회와 등록을 반복하지 않는다")
  void requestsDueCutoffOncePerTarget() {
    var store = mock(ReconciliationStore.class);
    var service = mock(ReconciliationService.class);
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(TARGET.plus(Duration.ofMinutes(31)));
    when(store.latestCutoff())
        .thenReturn(Optional.of(TARGET.minus(Duration.ofDays(2))))
        .thenReturn(Optional.of(TARGET.minus(Duration.ofDays(1))))
        .thenReturn(Optional.of(TARGET));
    var scheduler = new ReconciliationScheduler(service, clock, store);

    for (int i = 0; i < 5; i++) scheduler.poll();

    verify(service).request(TARGET.minus(Duration.ofDays(1)), "scheduler");
    verify(service).request(TARGET, "scheduler");
    verify(service, times(2)).request(any(), any());
    verify(store, times(2)).latestCutoff();
    verify(service, times(5)).tick();

    when(clock.instant()).thenReturn(TARGET.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(31)));
    scheduler.poll();

    verify(service).request(TARGET.plus(Duration.ofDays(1)), "scheduler");
  }
}
