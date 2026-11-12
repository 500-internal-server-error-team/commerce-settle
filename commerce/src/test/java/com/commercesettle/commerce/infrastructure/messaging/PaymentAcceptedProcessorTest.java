package com.commercesettle.commerce.infrastructure.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.service.PaymentAcceptedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAcceptedProcessorTest {
  @Test
  @DisplayName("묶음을 가득 채우면 다음 주기를 기다리지 않고 남은 대기 건을 이어서 처리한다")
  void continuesAfterFullBatchUntilQueueIsEmpty() {
    var service = mock(PaymentAcceptedService.class);
    when(service.processNext()).thenReturn(true, true, true, true, true, false);

    new PaymentAcceptedProcessor(service, 2).run();

    verify(service, times(6)).processNext();
  }
}
