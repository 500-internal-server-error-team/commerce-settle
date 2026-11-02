package com.commercesettle.settlement.application.port;

import java.util.List;
import java.util.UUID;

/** Settlement 발행 Outbox의 선점과 결과 저장. 모든 메서드는 호출자의 트랜잭션에 참여한다. Payment와 같은 방식이다. */
public interface OutboxStore {
  List<Delivery> claim(int limit, long leaseMs, int maxAttempts);

  boolean markPublished(Delivery delivery, Publication publication);

  boolean markFailed(Delivery delivery, int maxAttempts);

  record Publication(String topic, int partition, long offset) {}

  record Delivery(UUID id, long version, String topic, String key, byte[] body) {
    public Delivery {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }
}
