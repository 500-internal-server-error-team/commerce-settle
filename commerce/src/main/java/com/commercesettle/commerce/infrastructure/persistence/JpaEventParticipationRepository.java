package com.commercesettle.commerce.infrastructure.persistence;

import com.commercesettle.commerce.domain.model.EventParticipation;
import com.commercesettle.commerce.domain.model.EventParticipationStatus;
import com.commercesettle.commerce.domain.repository.EventParticipationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
class JpaEventParticipationRepository implements EventParticipationRepository {
  private final SpringDataEventParticipationRepository participations;
  private final JdbcOperations jdbc;

  JpaEventParticipationRepository(
      SpringDataEventParticipationRepository participations, JdbcOperations jdbc) {
    this.participations = participations;
    this.jdbc = jdbc;
  }

  /** 같은 구매자의 다른 참여가 커밋되기를 기다리는 유일 인덱스 대기도 잠금 대기 상한 안에서 끝낸다. */
  @Override
  public EventParticipation save(EventParticipation participation) {
    TransactionLimits.apply(jdbc);
    return participations.saveAndFlush(participation);
  }

  @Override
  public Optional<EventParticipation> findByOrderId(UUID orderId) {
    return participations.findByOrderId(orderId);
  }

  @Override
  public boolean existsActive(UUID eventId, UUID buyerId) {
    return participations.existsByEventIdAndBuyerIdAndStatusNot(
        eventId, buyerId, EventParticipationStatus.RELEASED);
  }
}
