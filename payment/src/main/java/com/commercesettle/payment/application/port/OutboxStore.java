package com.commercesettle.payment.application.port;

import com.commercesettle.observability.AsyncObservation.Timing;
import java.util.List;
import java.util.UUID;

public interface OutboxStore {
  List<Delivery> claim(int limit, long leaseMs, int maxAttempts);

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
      Timing timing) {
    public Delivery(UUID id, long version, String topic, String key, byte[] body) {
      this(id, version, topic, key, body, null, null);
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
