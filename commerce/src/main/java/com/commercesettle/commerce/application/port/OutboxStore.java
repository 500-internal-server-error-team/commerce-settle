package com.commercesettle.commerce.application.port;

import com.commercesettle.observability.AsyncObservation.Timing;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxStore {
  List<Delivery> claim(int limit, long leaseMs, int maxAttempts);

  /** 시도를 소진한 미발행 작업을 수동 확인으로 전환한다. 잠긴 행은 건너뛰며 전환한 건수를 돌려준다. */
  int expireExhausted(int limit, int maxAttempts);

  boolean markPublished(Delivery delivery, Publication publication);

  boolean markFailed(Delivery delivery, int maxAttempts);

  record Publication(String topic, int partition, long offset) {}

  record Delivery(
      UUID id,
      long version,
      String topic,
      String key,
      byte[] body,
      String traceParent,
      Timing timing,
      Instant orderAcceptedAt,
      boolean successorWaiting) {
    public Delivery(
        UUID id,
        long version,
        String topic,
        String key,
        byte[] body,
        String traceParent,
        Timing timing,
        Instant orderAcceptedAt) {
      this(id, version, topic, key, body, traceParent, timing, orderAcceptedAt, false);
    }

    public Delivery(
        UUID id,
        long version,
        String topic,
        String key,
        byte[] body,
        String traceParent,
        Timing timing) {
      this(id, version, topic, key, body, traceParent, timing, null, false);
    }

    public Delivery(UUID id, long version, String topic, String key, byte[] body) {
      this(id, version, topic, key, body, null, null, null, false);
    }

    public Delivery {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }
}
