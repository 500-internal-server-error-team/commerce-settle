package com.commercesettle.payment.application.port;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 금융 실행을 하지 않는 소유 DB의 일관된 원환불 읽기. DECLINED는 재실행하지 않는 종결 상태다. */
public interface RefundClosureQuery {
  Optional<Evidence> find(UUID refundId);

  record Evidence(
      UUID refundId,
      UUID requestId,
      UUID paymentId,
      UUID orderId,
      UUID buyerId,
      String status,
      String externalStatus,
      boolean mileageNotRestored,
      long version,
      OffsetDateTime decidedAt) {}
}
