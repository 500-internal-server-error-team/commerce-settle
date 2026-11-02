package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.EventParticipation;
import java.util.Optional;
import java.util.UUID;

public interface EventParticipationRepository {
  /**
   * 변경을 즉시 DB에 반영해 주문당 한 행과 구매자당 진행 중·확정 참여 1건 제약을 드러낸다. 같은 구매자의 동시 참여는 먼저 커밋한 쪽만 남고 나머지는
   * DataIntegrityViolationException이다.
   */
  EventParticipation save(EventParticipation participation);

  Optional<EventParticipation> findByOrderId(UUID orderId);

  /** 해제(RELEASED)가 아닌 참여가 있는지. */
  boolean existsActive(UUID eventId, UUID buyerId);
}
