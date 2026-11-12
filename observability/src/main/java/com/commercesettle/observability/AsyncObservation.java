package com.commercesettle.observability;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/** 저장된 작업의 기술적 관측 경계. 금융 원문·식별 규칙·업무 결과에는 관여하지 않는다. */
public interface AsyncObservation {
  AsyncObservation NOOP =
      new AsyncObservation() {
        @Override
        public String capture() {
          return null;
        }

        @Override
        public <T> T run(
            String operation,
            String parent,
            Map<String, String> ids,
            Timing timing,
            Supplier<T> action) {
          return action.get();
        }
      };

  /** 현재 부모의 W3C traceparent만 캡처한다. baggage·자격·업무 본문은 저장하지 않는다. */
  String capture();

  default <T> T run(
      String operation, String parent, Map<String, String> ids, Timing timing, Supplier<T> action) {
    try (Scope scope = start(operation, parent, ids, timing)) {
      try {
        return action.get();
      } catch (RuntimeException | Error failure) {
        scope.error(failure);
        throw failure;
      }
    }
  }

  default <T> T measure(
      String metric, String operation, String key, String value, Supplier<T> action) {
    return action.get();
  }

  default <T> T step(String operation, Supplier<T> action) {
    return action.get();
  }

  default void step(String operation, Runnable action) {
    step(
        operation,
        () -> {
          action.run();
          return null;
        });
  }

  /** 같은 실행 스레드에서 닫는다. 거래를 바꾸는 트랜잭션의 commit/rollback 뒤에 닫을 수 있다. */
  default Scope start(String operation, String parent, Map<String, String> ids, Timing timing) {
    return Scope.NOOP;
  }

  /** 이미 열린 트랜잭션이면 commit/rollback까지 같은 스레드의 문맥을 유지한다. */
  default void closeAfterTransaction(Scope scope) {
    scope.close();
  }

  interface Scope extends AutoCloseable {
    Scope NOOP = () -> {};

    default void error(Throwable failure) {}

    @Override
    void close();
  }

  default void run(
      String operation, String parent, Map<String, String> ids, Timing timing, Runnable action) {
    run(
        operation,
        parent,
        ids,
        timing,
        () -> {
          action.run();
          return null;
        });
  }

  /** 선점 UPDATE 전의 값이다. RUNNING은 만료 임대 회수이며 RETRY/WAITING과 구분한다. */
  record Timing(
      Instant registeredAt,
      Instant changedAt,
      Instant eligibleAt,
      String previousStatus,
      int attempts) {
    /** 재처리 회차에는 과거 실행·수동 확인 대기를 새 첫 대기에 포함하지 않는다. */
    public Instant initialAt() {
      return changedAt == null ? registeredAt : changedAt;
    }
  }
}
