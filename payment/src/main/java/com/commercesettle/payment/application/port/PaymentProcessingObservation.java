package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/** 비동기 처리의 대기·실행 경계. 업무 식별자는 추적에만 연결한다. */
public interface PaymentProcessingObservation {
  PaymentProcessingObservation NOOP =
      new PaymentProcessingObservation() {
        @Override
        public <T> T observe(Stage stage, Supplier<T> action) {
          return action.get();
        }

        @Override
        public void identify(String name, UUID id) {}

        @Override
        public void queueWait(Queue queue, Instant registeredAt, int attempts) {}
      };

  <T> T observe(Stage stage, Supplier<T> action);

  default void observe(Stage stage, Runnable action) {
    observe(
        stage,
        () -> {
          action.run();
          return null;
        });
  }

  void identify(String name, UUID id);

  /** 첫 선점만 기록한다. 재시도 누적 경과를 새 대기 시간으로 중복 집계하지 않는다. */
  void queueWait(Queue queue, Instant registeredAt, int attempts);

  enum Stage {
    INBOX_PROCESS,
    INBOX_CLAIM,
    INBOX_APPLY,
    INBOX_FAILURE,
    APPROVAL_PROCESS,
    APPROVAL_LEASE,
    APPROVAL_PREPARE,
    APPROVAL_LEASE_CHECK,
    APPROVAL_GATEWAY,
    APPROVAL_RECORD
  }

  enum Queue {
    INBOX,
    APPROVAL
  }
}
