package com.commercesettle.settlement.application.port;

import com.commercesettle.observability.AsyncObservation.Timing;
import java.util.List;
import java.util.UUID;

/** Settlement 발행 Outbox의 선점과 결과 저장. 모든 메서드는 호출자의 트랜잭션에 참여한다. Payment와 같은 방식이다. */
public interface OutboxStore {
  List<Delivery> claim(int limit, long leaseMs, int maxAttempts);

  /** 시도를 다 쓰고 기한이 지났거나 임대가 끝난 미발행 행을 REVIEW_REQUIRED로 남기고 그 수를 돌려준다. */
  int reviewExhausted(int limit, int maxAttempts);

  /** 임대를 가진 실행의 발행 결과를 저장한다. 같은 집계의 다음 미발행 이벤트가 있으면 알린다. */
  PublishResult markPublished(Delivery delivery, Publication publication);

  boolean markFailed(Delivery delivery, int maxAttempts);

  record Publication(String topic, int partition, long offset) {}

  /** STALE은 임대를 잃어 저장하지 않음. PUBLISHED_WITH_SUCCESSOR는 같은 집계의 뒤 이벤트가 이 발행을 기다리던 경우다. */
  enum PublishResult {
    STALE,
    PUBLISHED,
    PUBLISHED_WITH_SUCCESSOR
  }

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
