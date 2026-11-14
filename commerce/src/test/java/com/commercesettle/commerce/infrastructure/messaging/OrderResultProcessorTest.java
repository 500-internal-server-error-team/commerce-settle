package com.commercesettle.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.service.OrderResultService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderResultProcessorTest {
  @Test
  @DisplayName("담당 Inbox의 저장 신호로 깨어나 묶음 이후 작업도 이어 처리한다")
  void drainsOwnInboxAfterWakeup() throws Exception {
    var service = mock(OrderResultService.class);
    var calls = new AtomicInteger();
    when(service.processNext()).thenAnswer(i -> calls.incrementAndGet() <= 5);
    var processor = new OrderResultProcessor(service, 2, 1);
    try {
      processor.onWorkAvailable(new PaymentWorkAvailable(!false));
      verifyNoInteractions(service);
      processor.onWorkAvailable(new PaymentWorkAvailable(false));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(calls.get()).isEqualTo(6));
    } finally {
      processor.stop();
    }
  }
}
