package com.commercesettle.payment.infrastructure.observability;

import com.commercesettle.observability.AsyncObservation;
import com.commercesettle.observability.AsyncObservation.Timing;
import com.commercesettle.observability.infrastructure.MicrometerAsyncObservation;
import com.commercesettle.payment.application.port.PaymentProcessingObservation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 실행 슬롯 안에서 추적을 열고 닫는다. SQL 지표에는 실행 단계만 붙이며 원문·업무 ID는 label로 쓰지 않는다. */
@Component
public final class PaymentProcessingObservations implements PaymentProcessingObservation {
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final Clock clock;
  private AsyncObservation async = AsyncObservation.NOOP;

  public PaymentProcessingObservations(
      ObservationRegistry observations, MeterRegistry meters, Clock clock) {
    this.observations = observations;
    this.meters = meters;
    this.clock = clock;
    this.async =
        new MicrometerAsyncObservation(observations, Tracer.NOOP, Propagator.NOOP, meters, clock);
  }

  @Autowired(required = false)
  void asyncObservation(AsyncObservation observation) {
    this.async = observation;
  }

  @Override
  public <T> T observeStored(
      Stage stage, String parent, Map<String, String> ids, Timing timing, Supplier<T> action) {
    String name = "payment." + stage.name().toLowerCase(Locale.ROOT).replace('_', '.');
    return async.run(name, parent, ids, timing, () -> observe(stage, action));
  }

  @Override
  public <T> T observe(Stage stage, Supplier<T> action) {
    String name = stage.name().toLowerCase(Locale.ROOT).replace('_', '.');
    return async.measure("payment.processing", "payment." + name, "stage", name, action);
  }

  @Override
  public void identify(String name, UUID id) {
    try {
      Observation current = observations.getCurrentObservation();
      if (current != null && id != null) current.highCardinalityKeyValue(name, id.toString());
    } catch (RuntimeException unavailable) {
      // 식별자 계측은 업무 결과를 바꾸지 않는다.
    }
  }

  @Override
  public void queueWait(Queue queue, Instant registeredAt, int attempts) {
    if (attempts != 1) return;
    try {
      Duration wait = Duration.between(registeredAt, clock.instant());
      if (wait.isNegative()) return;
      Timer.builder("payment.queue.wait")
          .description("Current queue episode to first successful claim; excludes later retries")
          .tag("queue", queue.name().toLowerCase(Locale.ROOT))
          .register(meters)
          .record(wait);
    } catch (RuntimeException unavailable) {
      // 관측 기록 실패가 이미 선점한 업무를 멈추지 않는다.
    }
  }
}
