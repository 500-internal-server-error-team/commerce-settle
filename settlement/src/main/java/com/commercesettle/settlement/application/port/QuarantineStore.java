package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.application.dto.PageView;
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

  /**
   * 운영 목록. createdAt DESC, id DESC 순서다. deliveryStatus가 있으면 그 상태의 발행 작업을 가진 원문만 고른다. 원문·키 bytes는 읽지
   * 않는다.
   */
  PageView<QuarantineView> findPage(String deliveryStatus, int page, int size);

  Optional<QuarantineView> find(UUID quarantineId);

  Optional<DeliveryView> findDelivery(UUID deliveryId);

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

  /** 발행 작업과 재개 이력. 작업은 createdAt, id 순서, 이력은 createdAt, id 순서다. */
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
