package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.exception.DemoRunException;
import com.commercesettle.commerce.application.port.AuditLog;
import com.commercesettle.commerce.application.port.DemoRunStore;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DemoRunServiceTest {
  private final DemoRunStore store = mock(DemoRunStore.class);
  private final OrderWorkflowService workflow = mock(OrderWorkflowService.class);

  private DemoRunService service(boolean enabled, boolean isolated, boolean automatic) {
    return new DemoRunService(
        store,
        mock(IdempotentRequests.class),
        mock(ProductRepository.class),
        workflow,
        Clock.systemUTC(),
        mock(AuditLog.class),
        enabled,
        isolated,
        automatic);
  }

  @Test
  @DisplayName("기본 비활성에서는 회차 명령을 거절하고 격리·수동 발송 설정을 기동 전에 검사한다")
  void validatesEnvironmentAndDefaultOff() {
    assertThatThrownBy(() -> service(false, false, false).view(UUID.randomUUID()))
        .isInstanceOf(DemoRunException.class)
        .extracting("code")
        .isEqualTo("DEMO_DISABLED");
    assertThatThrownBy(() -> service(true, false, false).initialize())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service(true, true, true).initialize())
        .isInstanceOf(IllegalStateException.class);
    service(true, true, false).initialize();
    verify(store).requireEnvironment(true);
  }

  @Test
  @DisplayName("회차 한도 초과는 주문을 연결하기 전에 거절한다")
  void rejectsCapacityBeforeCapturingOrder() {
    UUID id = UUID.randomUUID(), seller = UUID.randomUUID(), product = UUID.randomUUID();
    var order = mock(com.commercesettle.commerce.domain.model.Order.class);
    when(order.getSellerId()).thenReturn(seller);
    when(order.getProductId()).thenReturn(product);
    when(store.find(id, true))
        .thenReturn(
            Optional.of(
                new DemoRunStore.Run(
                    id, seller, product, null, 5, 10, Instant.now(), null, List.of())));
    when(store.atCapacity(id)).thenReturn(true);
    assertThatThrownBy(() -> service(true, true, false).apply(order, id))
        .isInstanceOf(DemoRunException.class)
        .extracting("code")
        .isEqualTo("DEMO_LIMIT_EXCEEDED");
    org.mockito.Mockito.verify(order, org.mockito.Mockito.never())
        .configureDemo(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  @DisplayName("50건 상한·진행 위치·미도래 건수를 반환하고 나머지 고정 대상부터 이어 처리한다")
  void boundsPagesAndReportsWaitingWithoutLosingProgress() {
    UUID id = UUID.randomUUID();
    List<UUID> orders = Stream.generate(UUID::randomUUID).limit(51).toList();
    when(store.find(id, false))
        .thenReturn(
            Optional.of(
                new DemoRunStore.Run(
                    id,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    5,
                    10,
                    Instant.now(),
                    Instant.now(),
                    orders)));
    doThrow(new DemoRunException("DEMO_NOT_READY", "wait"))
        .when(workflow)
        .demoStep(eq("operator:test"), anyString(), eq(id), eq(orders.getFirst()), eq(false));
    var service = service(true, true, false);
    var first = service.stage("operator:test", "stage-key", id, false, null);
    assertThat(first.nextCursor()).isEqualTo(orders.get(49));
    assertThat(first.processedCount()).isEqualTo(49);
    assertThat(first.waitingCount()).isEqualTo(1);
    var second = service.stage("operator:test", "stage-key", id, false, first.nextCursor());
    assertThat(second.nextCursor()).isNull();
    assertThat(second.processedCount()).isEqualTo(1);
    verify(workflow, times(51)).demoStep(anyString(), anyString(), eq(id), any(), anyBoolean());
    assertThatThrownBy(
            () -> service.stage("operator:test", "stage-key", id, false, UUID.randomUUID()))
        .isInstanceOf(DemoRunException.class);
  }
}
