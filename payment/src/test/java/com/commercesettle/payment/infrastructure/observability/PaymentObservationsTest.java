package com.commercesettle.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.observability.infrastructure.MicrometerAsyncObservation;
import com.commercesettle.observability.infrastructure.ObservationErrors;
import com.commercesettle.payment.application.port.PaymentProcessingObservation.Queue;
import com.commercesettle.payment.application.port.PaymentProcessingObservation.Stage;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentObservationsTest {
  private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");
  private final MockClock ticker = new MockClock();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, ticker);
  private final ObservationRegistry registry = ObservationRegistry.create();
  private final List<Observation.Context> completed = new ArrayList<>();
  private final PaymentProcessingObservations observations =
      new PaymentProcessingObservations(registry, meters, Clock.fixed(NOW, ZoneOffset.UTC));

  PaymentObservationsTest() {
    registry.observationConfig().observationHandler(ObservationErrors.handler());
    registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    registry.observationConfig().observationFilter(MicrometerAsyncObservation.sqlStage());
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                completed.add(context);
              }
            });
  }

  @Test
  @DisplayName("관측 시작·대기 Timer 등록 실패가 업무를 막거나 두 번 실행하지 않는다")
  void telemetryFailureDoesNotBlockOrRepeatBusinessWork() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStart(Observation.Context context) {
                throw new IllegalStateException("observer unavailable");
              }
            });
    observations.observe(Stage.INBOX_PROCESS, calls::incrementAndGet);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(registry.getCurrentObservation()).isNull();
    var failing =
        new SimpleMeterRegistry() {
          @Override
          protected io.micrometer.core.instrument.Timer newTimer(
              io.micrometer.core.instrument.Meter.Id id,
              io.micrometer.core.instrument.distribution.DistributionStatisticConfig config,
              io.micrometer.core.instrument.distribution.pause.PauseDetector detector) {
            throw new IllegalStateException("meter unavailable");
          }
        };
    try {
      var unavailable =
          new PaymentProcessingObservations(registry, failing, Clock.fixed(NOW, ZoneOffset.UTC));
      unavailable.queueWait(Queue.INBOX, NOW.minusSeconds(1), 1);
    } finally {
      failing.close();
    }
  }

  @Test
  @DisplayName("등록부터 선점까지 대기와 실행 시간을 나누고 식별자는 지표에 넣지 않는다")
  void separatesQueueWaitFromExecution() {
    UUID eventId = UUID.randomUUID();
    observations.observe(
        Stage.INBOX_PROCESS,
        () -> {
          observations.identify("event.id", eventId);
          observations.queueWait(Queue.INBOX, NOW.minusSeconds(32), 1);
          observations.observe(Stage.INBOX_APPLY, () -> ticker.add(Duration.ofMillis(20)));
        });

    assertThat(
            meters
                .get("payment.queue.wait")
                .tag("queue", "inbox")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(32);
    assertThat(
            meters
                .get("payment.processing")
                .tag("stage", "inbox.apply")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS))
        .isEqualTo(20);
    assertThat(completed)
        .anySatisfy(
            context -> {
              assertThat(context.getHighCardinalityKeyValue("event.id")).isNotNull();
              assertThat(context.getHighCardinalityKeyValue("event.id").getValue())
                  .isEqualTo(eventId.toString());
            });
    assertThat(meters.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .noneSatisfy(tag -> assertThat(tag.getKey()).endsWith(".id")));
    assertThat(registry.getCurrentObservation()).isNull();
  }

  @Test
  @DisplayName("재시도 경과와 음수 시각 차이는 첫 대기 표본에 섞지 않는다")
  void excludesRetriesAndClockSkewFromQueueWait() {
    observations.queueWait(Queue.APPROVAL, NOW.minusSeconds(5), 1);
    observations.queueWait(Queue.APPROVAL, NOW.minusSeconds(15), 2);
    observations.queueWait(Queue.APPROVAL, NOW.plusSeconds(1), 1);
    var timer = meters.get("payment.queue.wait").tag("queue", "approval").timer();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(5);
  }

  @Test
  @DisplayName("같은 실행 스레드의 실패 뒤에도 추적 문맥과 오류가 다음 작업에 남지 않는다")
  void clearsContextAfterFailureOnReusedThread() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(
              () -> {
                assertThatThrownBy(
                        () ->
                            observations.observe(
                                Stage.INBOX_APPLY,
                                () -> {
                                  throw new IllegalStateException("rollback");
                                }))
                    .isInstanceOf(IllegalStateException.class);
                assertThat(registry.getCurrentObservation()).isNull();
              })
          .get(5, TimeUnit.SECONDS);
      executor
          .submit(
              () ->
                  observations.observe(
                      Stage.INBOX_APPLY,
                      () ->
                          assertThat(
                                  registry
                                      .getCurrentObservation()
                                      .getContextView()
                                      .getParentObservation())
                              .isNull()))
          .get(5, TimeUnit.SECONDS);
    }
    assertThat(
            meters
                .get("payment.processing")
                .tag("stage", "inbox.apply")
                .tag("error", "ObservationFailure")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            meters
                .get("payment.processing")
                .tag("stage", "inbox.apply")
                .tag("error", "none")
                .timer()
                .count())
        .isEqualTo(1);
  }
}
