package com.commercesettle.commerce.infrastructure.config;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.service.OrderWorkflowService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderWorkflowConfigurationTest {
  @Test
  @DisplayName("실패한 앞 묶음이 있어도 뒤의 미처리 주문을 같은 실행에서 계속 조회한다")
  void continuesPastFailedOrderAndFirstBatch() {
    var workflow = mock(OrderWorkflowService.class);
    var first = UUID.randomUUID();
    var later = UUID.randomUUID();
    when(workflow.due(100, null)).thenReturn(List.of(first));
    when(workflow.due(100, first)).thenReturn(List.of(later));
    when(workflow.due(100, later)).thenReturn(List.of());
    doThrow(new IllegalStateException("temporary")).when(workflow).advance(first);

    new OrderWorkflowConfiguration.OrderClock(workflow).run();

    verify(workflow).advance(later);
  }
}
