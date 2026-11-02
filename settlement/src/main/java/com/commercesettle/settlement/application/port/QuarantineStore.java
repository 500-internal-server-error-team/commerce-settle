package com.commercesettle.settlement.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 해석할 수 없는 수신 원문과 DLT 발행 작업의 저장소. 모든 메서드는 호출자의 트랜잭션에 참여한다. Payment 격리와 같은 방식이다. */
public interface QuarantineStore {
  /** 원문과 DLT 발행 작업을 저장한다. 같은 위치의 같은 원문은 기존 기록을 돌려주고, 다른 bytes면 예외를 던진다. */
  UUID capture(Source source, String errorCode, Instant now);

  /** 원문 버전이 그대로이고 진행 중인 REPLAY가 없을 때만 원래 토픽으로 다시 보낼 REPLAY 작업을 만든다. 버전이 바뀌었거나 진행 중이면 빈 값이다. */
  Optional<UUID> requestReplay(
      UUID quarantineId, long expectedVersion, String operator, String reasonCode, Instant now);

  /** 시도를 다 쓴 발행 작업을 같은 작업으로 재개하고 이전 시도 수를 이력에 남긴다. 버전이나 상태가 다르면 빈 값이다. */
  Optional<UUID> resumeDelivery(
      UUID deliveryId, long expectedVersion, String operator, String reasonCode, Instant now);

  /** 발행할 작업을 임대한다. 시도 횟수를 다 쓴 작업은 REVIEW_REQUIRED로 남긴다. */
  List<Delivery> claim(int limit, long leaseMs, int maxAttempts, Instant now);

  /** 임대가 그대로일 때만 결과를 저장한다. 임대가 바뀌었으면 false. */
  boolean published(Delivery delivery, Instant now);

  boolean failed(Delivery delivery, Instant now);

  record Source(
      String consumer, String topic, int partition, long offset, String key, byte[] body) {
    public Source {
      if (consumer == null
          || consumer.isBlank()
          || consumer.length() > 100
          || topic == null
          || topic.isBlank()
          || topic.length() > 249
          || partition < 0
          || offset < 0) {
        throw new IllegalArgumentException("Invalid quarantine source");
      }
      body = body == null ? null : body.clone();
    }

    @Override
    public byte[] body() {
      return body == null ? null : body.clone();
    }
  }

  record Delivery(
      UUID id,
      UUID quarantineId,
      long version,
      String topic,
      String key,
      byte[] body,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset) {
    public Delivery {
      body = body == null ? null : body.clone();
    }

    @Override
    public byte[] body() {
      return body == null ? null : body.clone();
    }
  }
}
