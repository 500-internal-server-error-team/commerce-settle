package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.OrderCapacityExceededException;
import com.commercesettle.commerce.application.port.AdmissionMetrics;
import com.commercesettle.commerce.domain.model.OrderAdmissionState;
import java.time.Clock;

/** 요청 경로에서는 메모리 상태만 확인하며 원격 관측은 별도 실행기가 갱신한다. */
public class OrderAdmissionService {
  private final OrderAdmissionState state;
  private final AdmissionMetrics metrics;
  private final Clock clock;
  private final boolean paused;

  public OrderAdmissionService(
      OrderAdmissionState state, AdmissionMetrics metrics, Clock clock, boolean paused) {
    this.state = state;
    this.metrics = metrics;
    this.clock = clock;
    this.paused = paused;
  }

  public void refresh() {
    try {
      if (paused) state.unavailable();
      else state.observe(metrics.read(), clock.instant());
    } catch (RuntimeException e) {
      state.unavailable();
    }
  }

  public void requireAdmission() {
    if (paused || !state.permits(clock.instant())) throw new OrderCapacityExceededException();
  }

  public boolean isOpen() {
    return !paused && state.permits(clock.instant());
  }
}
