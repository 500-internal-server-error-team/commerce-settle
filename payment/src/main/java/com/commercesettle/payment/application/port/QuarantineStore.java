package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 처리 불가 원문과 DLT 발행·운영 재처리 작업의 영속 저장 경계. */
public interface QuarantineStore {
  UUID capture(Source source, String errorCode, Instant now);

  Optional<UUID> requestReplay(
      UUID id, long expectedVersion, String operator, String reasonCode, Instant now);

  Optional<UUID> resumeDelivery(
      UUID id, long expectedVersion, String operator, String reasonCode, Instant now);

  List<Delivery> claim(int limit, long leaseMs, int maxAttempts, Instant now);

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
      String kind,
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
