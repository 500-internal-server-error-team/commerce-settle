package com.commercesettle.observability.infrastructure;

import com.commercesettle.observability.AsyncObservation;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** W3C 부모를 새 실행에서 복원하고 현재 스레드에서만 scope를 소유한다. */
public final class MicrometerAsyncObservation implements AsyncObservation {
  private static final Pattern PARENT =
      Pattern.compile("00-(?!0{32}-)[0-9a-f]{32}-(?!0{16}-)[0-9a-f]{16}-0[01]");
  private static final Pattern ID =
      Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
  private final ObservationRegistry observations;
  private final Tracer tracer;
  private final Propagator propagator;
  private final MeterRegistry meters;
  private final Clock clock;

  public MicrometerAsyncObservation(
      ObservationRegistry observations,
      Tracer tracer,
      Propagator propagator,
      MeterRegistry meters,
      Clock clock) {
    this.observations = observations;
    this.tracer = tracer;
    this.propagator = propagator;
    this.meters = meters;
    this.clock = clock;
  }

  public static ObservationFilter sqlStage() {
    return context -> {
      if (!"jdbc.query".equals(context.getName())) return context;
      ObservationView parent = context.getParentObservation();
      while (parent != null) {
        KeyValue stage = parent.getContextView().getLowCardinalityKeyValue("stage");
        if (stage != null) return context.addLowCardinalityKeyValue(stage);
        KeyValue operation = parent.getContextView().getLowCardinalityKeyValue("operation");
        if (operation != null)
          return context.addLowCardinalityKeyValue(KeyValue.of("stage", operation.getValue()));
        parent = parent.getContextView().getParentObservation();
      }
      return context.addLowCardinalityKeyValue(KeyValue.of("stage", "unscoped"));
    };
  }

  @Override
  public String capture() {
    try {
      Span span = tracer.currentSpan();
      if (span == null) return null;
      Map<String, String> carrier = new HashMap<>();
      propagator.inject(span.context(), carrier, Map::put);
      String parent = carrier.get("traceparent");
      return valid(parent) ? parent : null;
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  @Override
  public Scope start(String operation, String parent, Map<String, String> ids, Timing timing) {
    Observation observation = null;
    Observation.Scope scope = null;
    Tracer.SpanInScope detached = null;
    try {
      detached = tracer.withSpan(null);
      Map<String, String> carrier = valid(parent) ? Map.of("traceparent", parent) : Map.of();
      ReceiverContext<Map<String, String>> context = new ReceiverContext<>(Map::get);
      context.setCarrier(carrier);
      observation =
          Observation.createNotStarted("async.work", () -> context, observations)
              .parentObservation(null)
              .contextualName(operation)
              .lowCardinalityKeyValue("operation", operation);
      for (var id : ids.entrySet()) {
        if (id.getKey().endsWith(".id")
            && id.getValue() != null
            && ID.matcher(id.getValue()).matches()) {
          observation.highCardinalityKeyValue(id.getKey(), id.getValue());
        }
      }
      observation.start();
      scope = observation.openScope();
    } catch (RuntimeException unavailable) {
      close(scope, observation, detached);
      return Scope.NOOP;
    }
    try {
      recordQueue(operation, parent, timing);
    } catch (RuntimeException unavailable) {
      // 대기 시각이나 metric/exporter 오류가 실행·추적 문맥을 바꾸지 않는다.
    }
    Observation active = observation;
    Observation.Scope activeScope = scope;
    Tracer.SpanInScope previous = detached;
    return new Scope() {
      private boolean closed;
      private boolean errorRecorded;

      @Override
      public void error(Throwable failure) {
        if (closed || errorRecorded) return;
        errorRecorded = true;
        try {
          active.error(
              new ObservationErrors.ObservationFailure(failure.getClass().getSimpleName()));
        } catch (RuntimeException unavailable) {
          // 원래 업무 예외만 호출자에게 전달한다.
        }
      }

      @Override
      public void close() {
        if (closed) return;
        closed = true;
        MicrometerAsyncObservation.close(activeScope, active, previous);
      }
    };
  }

  @Override
  public <T> T step(String operation, Supplier<T> action) {
    return measure("async.stage", operation, "stage", operation, action);
  }

  @Override
  public <T> T measure(
      String metric, String operation, String key, String value, Supplier<T> action) {
    Observation observation = null;
    Observation.Scope scope = null;
    try {
      observation =
          Observation.createNotStarted(metric, observations)
              .contextualName(operation)
              .lowCardinalityKeyValue(key, value)
              .start();
      scope = observation.openScope();
    } catch (RuntimeException unavailable) {
      close(scope, observation, null);
      return action.get();
    }
    try {
      return action.get();
    } catch (RuntimeException | Error failure) {
      try {
        observation.error(
            new ObservationErrors.ObservationFailure(failure.getClass().getSimpleName()));
      } catch (RuntimeException unavailable) {
        // 원래 업무 예외만 전파한다.
      }
      throw failure;
    } finally {
      close(scope, observation, null);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void closeAfterTransaction(Scope scope) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      scope.close();
      return;
    }
    Deque<Scope> created = null;
    try {
      Deque<Scope> scopes = (Deque<Scope>) TransactionSynchronizationManager.getResource(this);
      if (scopes == null) {
        scopes = new ArrayDeque<>();
        Deque<Scope> owned = scopes;
        TransactionSynchronizationManager.bindResource(this, owned);
        created = owned;
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void suspend() {
                TransactionSynchronizationManager.unbindResource(MicrometerAsyncObservation.this);
              }

              @Override
              public void resume() {
                TransactionSynchronizationManager.bindResource(
                    MicrometerAsyncObservation.this, owned);
              }

              @Override
              public void afterCompletion(int status) {
                try {
                  while (!owned.isEmpty()) {
                    Scope current = owned.pop();
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                      current.error(new TransactionNotCommitted());
                    }
                    current.close();
                  }
                } finally {
                  TransactionSynchronizationManager.unbindResourceIfPossible(
                      MicrometerAsyncObservation.this);
                }
              }
            });
      }
      scopes.push(scope);
    } catch (RuntimeException unavailable) {
      if (created != null && TransactionSynchronizationManager.getResource(this) == created) {
        TransactionSynchronizationManager.unbindResourceIfPossible(this);
      }
      scope.close();
    }
  }

  private void recordQueue(String queue, String parent, Timing timing) {
    if (timing == null) return;
    Instant now = clock.instant();
    String kind =
        timing.attempts() <= 1
            ? "initial"
            : switch (timing.previousStatus() == null ? "" : timing.previousStatus()) {
              case "RUNNING", "CLAIMED" -> "lease-recovery";
              case "RETRY", "WAITING_RETRY" -> "retry";
              default -> "unknown";
            };
    record("async.queue.ready.delay", queue, kind, timing.eligibleAt(), now);
    Instant entered =
        "initial".equals(kind)
            ? timing.initialAt()
            : "retry".equals(kind) ? timing.changedAt() : timing.eligibleAt();
    record("async.queue.wait", queue, kind, entered, now);
    if ("retry".equals(kind)) {
      record("async.queue.backoff", queue, kind, timing.changedAt(), timing.eligibleAt());
    }
    // queue와 실제 처리는 저장 당시 부모를 공유하는 형제 span이다.
    if (valid(parent) && entered != null && !entered.isAfter(now)) {
      try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
        Span span =
            propagator
                .extract(Map.of("traceparent", parent), Map::get)
                .name(queue + ".wait")
                .tag("kind", kind)
                .startTimestamp(
                    TimeUnit.SECONDS.toMicros(entered.getEpochSecond()) + entered.getNano() / 1000,
                    TimeUnit.MICROSECONDS)
                .start();
        span.end();
      }
    }
  }

  private void record(String name, String queue, String kind, Instant start, Instant end) {
    if (start == null || end == null || end.isBefore(start)) return;
    Timer.builder(name)
        .tags("queue", queue, "kind", kind)
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofMillis(1))
        .maximumExpectedValue(Duration.ofMinutes(5))
        .register(meters)
        .record(Duration.between(start, end));
  }

  private static boolean valid(String parent) {
    return parent != null && PARENT.matcher(parent).matches();
  }

  private static void close(
      Observation.Scope scope, Observation observation, Tracer.SpanInScope detached) {
    try {
      if (scope != null) scope.close();
    } catch (RuntimeException unavailable) {
      // 각 자원의 종료는 독립적으로 시도한다.
    }
    try {
      if (observation != null) observation.stop();
    } catch (RuntimeException unavailable) {
      // 원래 업무 결과를 유지한다.
    }
    try {
      if (detached != null) detached.close();
    } catch (RuntimeException unavailable) {
      // 원래 업무 결과를 유지한다.
    }
  }

  private static final class TransactionNotCommitted extends RuntimeException {
    private TransactionNotCommitted() {
      super(null, null, false, false);
    }
  }
}
