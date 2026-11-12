package com.commercesettle.settlement.infrastructure.worker;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.exception.WaitingInboxFailedException;
import com.commercesettle.settlement.application.service.SettlementEventService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WaitingInboxSchedulerTest {
  private final SettlementEventService service = mock(SettlementEventService.class);
  private final WaitingInboxScheduler scheduler = new WaitingInboxScheduler(service, 100);

  @Test
  @DisplayName("판정이 실패한 행은 미루고 같은 주기의 다음 대기 건으로 넘어간다")
  void defersFailedRowAndContinues() {
    UUID failed = UUID.randomUUID();
    when(service.processNextWaiting())
        .thenThrow(new WaitingInboxFailedException(failed, new IllegalStateException("boom")))
        .thenReturn(true)
        .thenReturn(false);

    scheduler.poll();

    verify(service).deferFailedWaiting(failed);
    verify(service, times(3)).processNextWaiting();
  }

  @Test
  @DisplayName("실패한 행을 미루지 못하면 같은 행을 다시 잡지 않도록 이번 주기를 멈춘다")
  void stopsWhenDeferFails() {
    UUID failed = UUID.randomUUID();
    when(service.processNextWaiting())
        .thenThrow(new WaitingInboxFailedException(failed, new IllegalStateException("boom")));
    doThrow(new IllegalStateException("db down")).when(service).deferFailedWaiting(failed);

    scheduler.poll();

    verify(service, times(1)).processNextWaiting();
  }

  @Test
  @DisplayName("선점 자체가 실패하면 100번 반복하지 않고 이번 주기를 멈춘다")
  void stopsWhenClaimFails() {
    when(service.processNextWaiting()).thenThrow(new IllegalStateException("db down"));

    scheduler.poll();

    verify(service, times(1)).processNextWaiting();
    verify(service, never()).deferFailedWaiting(org.mockito.ArgumentMatchers.any());
  }
}
