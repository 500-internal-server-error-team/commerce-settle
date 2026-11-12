package com.commercesettle.payment.application.port;

import com.commercesettle.observability.AsyncObservation.Timing;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface OutboxStore {
  /** 발행 가능한 행을 선점한다. 시도 상한을 넘긴 행은 건너뛰며 확인 대상으로 바꾸지 않는다. */
  List<Delivery> claim(int limit, long leaseMs, int maxAttempts);

  /** 시도 상한을 넘겨 재시도할 수 없는 미발행 행을 최대 limit건 확인 대상으로 바꾸고 바꾼 건수를 돌려준다. */
  int reviewExhausted(int limit, int maxAttempts);

  /** 발행 결과를 한 트랜잭션에 저장하고, 아직 유효한 임대·버전이라 반영한 이벤트 ID를 돌려준다. */
  Set<UUID> markPublished(List<Published> results);

  boolean markFailed(Delivery delivery, int maxAttempts);

  record Publication(String topic, int partition, long offset) {}

  record Published(Delivery delivery, Publication publication) {}

  record Delivery(
      UUID id,
      long version,
      String topic,
      String key,
      byte[] body,
      String traceParent,
      Timing timing,
      Instant orderAcceptedAt) {
    public Delivery(
        UUID id,
        long version,
        String topic,
        String key,
        byte[] body,
        String traceParent,
        Timing timing) {
      this(id, version, topic, key, body, traceParent, timing, null);
    }

    public Delivery(UUID id, long version, String topic, String key, byte[] body) {
      this(id, version, topic, key, body, null, null, null);
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
