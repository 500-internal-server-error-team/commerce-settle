package com.commercesettle.payment.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 자기 서비스의 이벤트 메타데이터만 조회한다. 원문·업무 payload는 반환하지 않는다. */
public interface EventRecordReader {
  List<Entry> find(List<UUID> ids);

  record Entry(
      String kind,
      UUID id,
      UUID eventId,
      String eventType,
      UUID aggregateId,
      String status,
      String topic,
      String consumer,
      Integer partition,
      Long offset,
      int attempts,
      String lastErrorCode,
      UUID resultRef,
      Instant createdAt,
      Instant updatedAt,
      List<UUID> referenceIds) {}
}
