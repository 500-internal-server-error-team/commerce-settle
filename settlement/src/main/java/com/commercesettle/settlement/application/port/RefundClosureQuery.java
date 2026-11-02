package com.commercesettle.settlement.application.port;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** #154의 원예약을 읽는다. RELEASED 예약은 같은 환불로 재활성화하지 않는다. */
public interface RefundClosureQuery {
  Optional<Evidence> find(UUID refundId);

  record Evidence(
      UUID refundId,
      UUID reservationId,
      UUID paymentId,
      UUID orderId,
      String status,
      String itemStatus,
      UUID payoutId,
      boolean activeReservation,
      long reservationVersion,
      long itemVersion,
      OffsetDateTime releasedAt) {}
}
