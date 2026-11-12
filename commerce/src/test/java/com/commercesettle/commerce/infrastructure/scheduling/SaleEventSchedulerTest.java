package com.commercesettle.commerce.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.service.SaleEventService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 이벤트 시작·종료 스케줄러가 한 단계·한 이벤트의 실패로 나머지 처리를 멈추지 않는지 확인한다. */
class SaleEventSchedulerTest {
  private static final Instant NOW = Instant.parse("2033-01-01T00:00:00Z");
  private static final int BATCH = 10;

  private final SaleEventService events = mock(SaleEventService.class);
  private final SaleEventScheduler scheduler =
      new SaleEventScheduler(events, Clock.fixed(NOW, ZoneOffset.UTC), BATCH);

  @Test
  @DisplayName("시작 처리가 실패해도 같은 시각으로 종료 처리를 계속한다")
  void closesEndedEventsWhenOpeningFails() {
    UUID ended = UUID.randomUUID();
    when(events.openStarted(NOW, BATCH)).thenThrow(new IllegalStateException("lock timeout"));
    when(events.endedEventIds(NOW, BATCH)).thenReturn(List.of(ended));

    scheduler.run();

    verify(events).closeIfEnded(ended, NOW);
  }

  @Test
  @DisplayName("한 이벤트의 종료가 실패해도 다음 이벤트를 닫는다")
  void closesRemainingEventsWhenOneFails() {
    UUID failing = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(events.endedEventIds(NOW, BATCH)).thenReturn(List.of(failing, next));
    when(events.closeIfEnded(failing, NOW)).thenThrow(new IllegalStateException("lock timeout"));

    scheduler.run();

    verify(events).closeIfEnded(next, NOW);
  }

  @Test
  @DisplayName("묶음 크기가 1보다 작으면 만들지 않는다")
  void rejectsInvalidBatchSize() {
    assertThatThrownBy(() -> new SaleEventScheduler(events, Clock.fixed(NOW, ZoneOffset.UTC), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
