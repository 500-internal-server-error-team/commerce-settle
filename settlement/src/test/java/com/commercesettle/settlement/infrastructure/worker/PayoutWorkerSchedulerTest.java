package com.commercesettle.settlement.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.settlement.application.service.PayoutWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayoutWorkerSchedulerTest {
  @Test
  @DisplayName("한 작업에서 예외가 나도 폴링을 멈추지 않고 다음 작업을 실행한다")
  void continuesAfterJobFailure() {
    PayoutWorker worker = mock(PayoutWorker.class);
    when(worker.runOnce())
        .thenThrow(new IllegalStateException("injected failure"))
        .thenReturn(true)
        .thenReturn(false);
    PayoutWorkerScheduler scheduler = new PayoutWorkerScheduler(worker, 8);

    assertThatCode(scheduler::poll).doesNotThrowAnyException();

    verify(worker, times(3)).runOnce();
  }
}
