package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.OrderCapacityExceededException;
import com.commercesettle.commerce.application.port.AdmissionMetrics;
import com.commercesettle.commerce.domain.model.OrderAdmissionState;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Reason;
import com.commercesettle.commerce.domain.model.OrderAdmissionState.Transition;
import com.commercesettle.observability.RepeatedWarning;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 요청 경로에서는 메모리 상태만 확인하며 원격 관측은 별도 실행기가 갱신한다. */
public class OrderAdmissionService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final Logger LOGGER = LoggerFactory.getLogger(OrderAdmissionService.class);
  private final RepeatedWarning refreshFailed = new RepeatedWarning();
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
    long started = System.nanoTime();
    Transition change;
    RuntimeException failure = null;
    try {
      change =
          paused
              ? state.unavailable(Reason.OPERATOR_PAUSED, clock.instant())
              : state.observe(metrics.read(), clock.instant());
    } catch (RuntimeException e) {
      failure = e;
      change = state.unavailable(Reason.METRICS_UNAVAILABLE, clock.instant());
    }
    double refreshMs = (System.nanoTime() - started) / 1_000_000d;
    report(change, refreshMs);
    if (failure != null) reportFailure(failure, refreshMs);
  }

  public void requireAdmission() {
    if (!isOpen()) throw new OrderCapacityExceededException();
  }

  public boolean isOpen() {
    if (paused) {
      report(state.unavailable(Reason.OPERATOR_PAUSED, clock.instant()), null);
      return false;
    }
    var decision = state.evaluate(clock.instant());
    report(decision.transition(), null);
    return decision.open();
  }

  private static void report(Transition change, Double refreshMs) {
    if (change == null) return;
    var log =
        change.open() || change.reason() == Reason.RECOVERING ? LOGGER.atDebug() : LOGGER.atWarn();
    log.addKeyValue("event", "commerce.order.admission")
        .addKeyValue("sequence", change.sequence())
        .addKeyValue("state", change.open() ? "OPEN" : "CLOSED")
        .addKeyValue("initial", change.initial())
        .addKeyValue("reason", change.reason())
        .addKeyValue("decidedAt", format(change.decidedAt()));
    if (change.key() != null) {
      log.addKeyValue("signal", change.key().signal())
          .addKeyValue("resource", change.key().resource());
    }
    if (change.value() != null)
      log.addKeyValue(
          "value", Double.isFinite(change.value()) ? change.value() : change.value().toString());
    if (change.observedAt() != null) log.addKeyValue("observedAt", format(change.observedAt()));
    if (change.limit() != null) log.addKeyValue("limit", change.limit());
    if (change.recoveryStartedAt() != null) {
      log.addKeyValue("recoveryStartedAt", format(change.recoveryStartedAt()))
          .addKeyValue(
              "observations",
              change.observations().stream()
                  .map(
                      sample ->
                          Map.of(
                              "signal",
                              sample.key().signal(),
                              "resource",
                              sample.key().resource(),
                              "value",
                              sample.value(),
                              "observedAt",
                              format(sample.observedAt())))
                  .toList());
    }
    if (refreshMs != null) log.addKeyValue("refreshDurationMs", refreshMs);
    log.log("Order admission state changed");
  }

  private void reportFailure(RuntimeException failure, double refreshMs) {
    var log =
        refreshFailed
            .at(LOGGER)
            .addKeyValue("event", "commerce.order.admission.refresh-failed")
            .addKeyValue("failedAt", format(clock.instant()))
            .addKeyValue("refreshDurationMs", refreshMs)
            .addKeyValue("errorType", failure.getClass().getSimpleName());
    String cause = causeType(failure);
    if (cause != null) log.addKeyValue("causeType", cause);
    log.log("Order admission metrics refresh failed");
  }

  private static String causeType(RuntimeException failure) {
    Throwable cause = failure.getCause();
    while ((cause instanceof ExecutionException || cause instanceof CompletionException)
        && cause.getCause() != null
        && cause != cause.getCause()) cause = cause.getCause();
    return cause == null ? null : cause.getClass().getSimpleName();
  }

  private static String format(Instant value) {
    return value.atZone(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }
}
