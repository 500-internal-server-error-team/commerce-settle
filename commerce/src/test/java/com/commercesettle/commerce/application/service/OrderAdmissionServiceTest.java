package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercesettle.commerce.application.exception.OrderCapacityExceededException;
import com.commercesettle.commerce.application.port.AdmissionMetrics;
import com.commercesettle.commerce.domain.model.OrderAdmissionState;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Key;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Sample;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Signal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderAdmissionServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
  private static final Key KEY = new Key(Signal.LAG, "commerce.v1");

  @Test
  @DisplayName("요청은 원격 관측을 다시 읽지 않고 표본 만료를 즉시 거절한다")
  void checksCachedStateWithoutRemoteReads() {
    Clock clock = mock(Clock.class);
    var reads = new AtomicInteger();
    AdmissionMetrics source =
        () -> {
          reads.incrementAndGet();
          return List.of(new Sample(KEY, 0, clock.instant()));
        };
    var service =
        new OrderAdmissionService(new OrderAdmissionState(Set.of(KEY)), source, clock, false);
    for (int seconds : new int[] {0, 30, 60}) {
      when(clock.instant()).thenReturn(NOW.plusSeconds(seconds));
      service.refresh();
    }
    for (int request = 0; request < 100; request++) service.requireAdmission();
    assertThat(reads.get()).isEqualTo(3);
    when(clock.instant()).thenReturn(NOW.plusSeconds(106));
    assertThatThrownBy(service::requireAdmission)
        .isInstanceOf(OrderCapacityExceededException.class);
    assertThat(reads.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("열린 상태의 관측 실패와 운영 중단은 모두 신규 접수를 닫는다")
  void closesOnCollectionFailureAndOperatorPause() {
    Clock clock = mock(Clock.class);
    AdmissionMetrics source = mock(AdmissionMetrics.class);
    var service =
        new OrderAdmissionService(new OrderAdmissionState(Set.of(KEY)), source, clock, false);
    for (int seconds : new int[] {0, 30, 60}) {
      Instant at = NOW.plusSeconds(seconds);
      when(clock.instant()).thenReturn(at);
      when(source.read()).thenReturn(List.of(new Sample(KEY, 0, at)));
      service.refresh();
    }
    assertThat(service.isOpen()).isTrue();
    when(source.read()).thenThrow(new IllegalStateException("unavailable"));
    service.refresh();
    assertThatThrownBy(service::requireAdmission)
        .isInstanceOf(OrderCapacityExceededException.class);
    var paused =
        new OrderAdmissionService(new OrderAdmissionState(Set.of(KEY)), source, clock, true);
    paused.refresh();
    assertThatThrownBy(paused::requireAdmission).isInstanceOf(OrderCapacityExceededException.class);
  }
}
