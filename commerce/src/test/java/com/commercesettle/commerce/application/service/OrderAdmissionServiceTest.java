package com.commercesettle.commerce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

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

  @ParameterizedTest
  @ValueSource(strings = {"direct", "execution", "completion"})
  @DisplayName("지표 조회 실패는 차단 전환과 별도로 실제 예외와 소요 시간을 기록한다")
  void recordsFailureWithoutSecretsOrRepeatedRequestLogs(String wrapper) {
    Clock clock = mock(Clock.class);
    AdmissionMetrics source = mock(AdmissionMetrics.class);
    var service = opened(source, clock);
    when(clock.instant()).thenReturn(NOW.plusSeconds(61));
    Throwable cause = new java.net.http.HttpTimeoutException("credential=hidden");
    if (wrapper.equals("execution")) cause = new java.util.concurrent.ExecutionException(cause);
    if (wrapper.equals("completion")) cause = new java.util.concurrent.CompletionException(cause);
    when(source.read())
        .thenThrow(new IllegalStateException("sensitive://host?token=hidden", cause));

    try (var logs = new Logs(ch.qos.logback.classic.Level.WARN)) {
      service.refresh();
      for (int i = 0; i < 100; i++)
        assertThatThrownBy(service::requireAdmission)
            .isInstanceOf(OrderCapacityExceededException.class);
      service.refresh();

      assertThat(logs.appender.list).hasSize(2);
      assertThat(fields(logs.appender.list.getFirst()))
          .containsEntry("reason", OrderAdmissionState.Reason.METRICS_UNAVAILABLE)
          .containsEntry("state", "CLOSED")
          .containsEntry("initial", false);
      var event = logs.appender.list.getLast();
      var fields = fields(event);
      assertThat(fields)
          .containsEntry("event", "commerce.order.admission.refresh-failed")
          .containsEntry("errorType", "IllegalStateException")
          .containsEntry("causeType", "HttpTimeoutException");
      assertThat((Double) fields.get("refreshDurationMs")).isGreaterThanOrEqualTo(0);
      var layout = new ch.qos.logback.classic.PatternLayout();
      layout.setContext(
          ((Logger) LoggerFactory.getLogger(OrderAdmissionService.class)).getLoggerContext());
      layout.setPattern("%kvp%n");
      layout.start();
      assertThat(layout.doLayout(event))
          .contains("+09:00", "commerce.order.admission.refresh-failed")
          .doesNotContain("sensitive://", "hidden");
      layout.stop();
      assertThat(event.getThrowableProxy()).isNull();
      assertThat(event.getFormattedMessage() + fields).doesNotContain("hidden", "sensitive://");
    }
  }

  @Test
  @DisplayName("요청 경로의 표본 만료는 사용한 값·시각을 한번 남기고 회복 뒤 재개를 기록한다")
  void recordsCachedExpiryAndRecovery() {
    Clock clock = mock(Clock.class);
    AdmissionMetrics source = mock(AdmissionMetrics.class);
    var service = opened(source, clock);
    when(clock.instant()).thenReturn(NOW.plusSeconds(106));

    try (var logs = new Logs()) {
      assertThatThrownBy(service::requireAdmission)
          .isInstanceOf(OrderCapacityExceededException.class);
      assertThat(service.isOpen()).isFalse();
      assertThat(fields(logs.appender.list.getFirst()))
          .containsEntry("reason", OrderAdmissionState.Reason.STALE_SAMPLE)
          .containsEntry("signal", Signal.LAG)
          .containsEntry("resource", "commerce.v1")
          .containsEntry("value", 0d)
          .containsEntry("observedAt", "2026-11-02T10:01:00+09:00")
          .containsEntry("decidedAt", "2026-11-02T10:01:46+09:00")
          .containsEntry("limit", 45d);
      for (int seconds : new int[] {107, 137, 167}) {
        Instant at = NOW.plusSeconds(seconds);
        when(clock.instant()).thenReturn(at);
        when(source.read()).thenReturn(List.of(new Sample(KEY, 0, at)));
        service.refresh();
      }
      assertThat(service.isOpen()).isTrue();
      assertThat(logs.appender.list).hasSize(2);
      assertThat(fields(logs.appender.list.getLast()))
          .containsEntry("state", "OPEN")
          .containsEntry("reason", OrderAdmissionState.Reason.OPEN)
          .containsEntry("recoveryStartedAt", "2026-11-02T10:01:47+09:00")
          .containsEntry(
              "observations",
              List.of(
                  java.util.Map.of(
                      "signal",
                      Signal.LAG,
                      "resource",
                      "commerce.v1",
                      "value",
                      0d,
                      "observedAt",
                      "2026-11-02T10:02:47+09:00")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"WARN", "DEBUG"})
  @DisplayName("캐시 만료 뒤 조회 실패도 기록하고 반복 실패는 DEBUG로 내린다")
  void recordsRefreshFailureAfterCachedExpiry(String level) {
    Clock clock = mock(Clock.class);
    AdmissionMetrics source = mock(AdmissionMetrics.class);
    var service = opened(source, clock);
    when(clock.instant()).thenReturn(NOW.plusSeconds(106));

    try (var logs = new Logs(ch.qos.logback.classic.Level.valueOf(level))) {
      assertThatThrownBy(service::requireAdmission)
          .isInstanceOf(OrderCapacityExceededException.class);
      when(clock.instant()).thenReturn(NOW.plusSeconds(107));
      when(source.read())
          .thenThrow(
              new IllegalStateException(
                  "sensitive://host?token=hidden",
                  new java.net.http.HttpTimeoutException("credential=hidden")));
      service.refresh();
      service.refresh();
      for (int i = 0; i < 100; i++)
        assertThatThrownBy(service::requireAdmission)
            .isInstanceOf(OrderCapacityExceededException.class);

      var transitions =
          logs.appender.list.stream()
              .filter(e -> "commerce.order.admission".equals(fields(e).get("event")))
              .toList();
      assertThat(transitions).hasSize(1);
      assertThat(fields(transitions.getFirst()))
          .containsEntry("reason", OrderAdmissionState.Reason.STALE_SAMPLE);
      var failures =
          logs.appender.list.stream()
              .filter(e -> "commerce.order.admission.refresh-failed".equals(fields(e).get("event")))
              .toList();
      assertThat(failures).hasSize(level.equals("WARN") ? 1 : 2);
      assertThat(failures)
          .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
          .hasSize(1);
      for (var failure : failures) {
        var values = fields(failure);
        assertThat(values)
            .containsEntry("failedAt", "2026-11-02T10:01:47+09:00")
            .containsEntry("errorType", "IllegalStateException")
            .containsEntry("causeType", "HttpTimeoutException");
        assertThat((Double) values.get("refreshDurationMs")).isGreaterThanOrEqualTo(0);
        assertThat(failure.getThrowableProxy()).isNull();
        assertThat(failure.getFormattedMessage() + values).doesNotContain("hidden", "sensitive://");
      }
    }
  }

  @Test
  @DisplayName("최초 준비 중 반복 조회 실패도 원인을 한번 남기며 운영 중단과 구분한다")
  void recordsInitialCollectionFailureAndPause() {
    Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
    AdmissionMetrics source =
        () -> {
          throw new IllegalStateException("not logged");
        };
    var service =
        new OrderAdmissionService(new OrderAdmissionState(Set.of(KEY)), source, clock, false);
    try (var logs = new Logs(ch.qos.logback.classic.Level.WARN)) {
      service.refresh();
      service.refresh();
      for (int i = 0; i < 100; i++) assertThat(service.isOpen()).isFalse();
      assertThat(logs.appender.list).hasSize(2);
      assertThat(fields(logs.appender.list.getFirst()))
          .containsEntry("initial", true)
          .containsEntry("reason", OrderAdmissionState.Reason.METRICS_UNAVAILABLE);
      var paused =
          new OrderAdmissionService(new OrderAdmissionState(Set.of(KEY)), source, clock, true);
      assertThat(paused.isOpen()).isFalse();
      paused.refresh();
      assertThat(logs.appender.list).hasSize(3);
      assertThat(fields(logs.appender.list.getLast()))
          .containsEntry("reason", OrderAdmissionState.Reason.OPERATOR_PAUSED);
    }
  }

  private static OrderAdmissionService opened(AdmissionMetrics source, Clock clock) {
    var service =
        new OrderAdmissionService(new OrderAdmissionState(Set.of(KEY)), source, clock, false);
    for (int seconds : new int[] {0, 30, 60}) {
      Instant at = NOW.plusSeconds(seconds);
      when(clock.instant()).thenReturn(at);
      when(source.read()).thenReturn(List.of(new Sample(KEY, 0, at)));
      service.refresh();
    }
    return service;
  }

  private static java.util.Map<String, Object> fields(ILoggingEvent event) {
    var fields = new java.util.HashMap<String, Object>();
    for (var pair : event.getKeyValuePairs()) fields.put(pair.key, pair.value);
    return fields;
  }

  private static final class Logs implements AutoCloseable {
    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderAdmissionService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ch.qos.logback.classic.Level previous = logger.getLevel();

    private Logs() {
      this(ch.qos.logback.classic.Level.DEBUG);
    }

    private Logs(ch.qos.logback.classic.Level level) {
      logger.setLevel(level);
      appender.start();
      logger.addAppender(appender);
    }

    @Override
    public void close() {
      logger.detachAppender(appender);
      logger.setLevel(previous);
      appender.stop();
    }
  }
}
