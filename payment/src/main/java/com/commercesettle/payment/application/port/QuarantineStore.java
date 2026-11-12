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

  /**
   * 운영 목록. createdAt DESC, id DESC 순서다. deliveryStatus가 있으면 그 상태의 발행 작업을 가진 원문만 고른다. 원문·키 bytes는 읽지
   * 않는다.
   */
  List<QuarantineView> findPage(String deliveryStatus, int page, int size);

  long count(String deliveryStatus);

  Optional<QuarantineView> find(UUID quarantineId);

  Optional<DeliveryView> findDelivery(UUID deliveryId);

  List<Delivery> claim(int limit, long leaseMs, int maxAttempts, Instant now);

  boolean published(Delivery delivery, Instant now);

  boolean failed(Delivery delivery, Instant now);

  /** 운영 조회용 원문 요약. 원문 대신 크기와 SHA-256만 담는다. 원문이 null이면 둘 다 null이다. */
  record QuarantineView(
      UUID id,
      String consumer,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      String errorCode,
      Integer bodySize,
      String bodySha256,
      long version,
      Instant createdAt,
      List<DeliveryView> deliveries) {
    public QuarantineView {
      deliveries = List.copyOf(deliveries);
    }
  }

  /** 발행 작업과 재개 이력. 작업과 이력은 각각 createdAt, id 순서다. */
  record DeliveryView(
      UUID id,
      UUID quarantineId,
      String kind,
      String topic,
      String status,
      int attempts,
      Instant nextRunAt,
      Instant leaseUntil,
      String operatorId,
      String reasonCode,
      long version,
      Instant createdAt,
      Instant updatedAt,
      List<ResumeView> resumes) {
    public DeliveryView {
      resumes = List.copyOf(resumes);
    }
  }

  record ResumeView(
      UUID id,
      long expectedVersion,
      int previousAttempts,
      String operatorId,
      String reasonCode,
      Instant createdAt) {}

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
