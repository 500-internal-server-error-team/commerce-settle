package com.commercesettle.settlement.domain.repository;

import com.commercesettle.settlement.domain.model.SettlementItemStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 환불 예약 저장소. 항목 상태 전이와 예약 행을 지급 예약과 같은 조건부 SQL로 함께 바꾼다(지급 선점과 같은 잠금 방식). 항목당 ACTIVE 예약은 부분 UNIQUE가
 * 마지막으로 막는다. 모든 메서드는 호출자의 트랜잭션에서 실행한다.
 */
public interface RefundReservationRepository {
  /** 결제의 정산 항목 현재 상태. 항목이 없으면 빈 값이다. */
  Optional<ItemState> findItem(UUID paymentId);

  /** 같은 항목·같은 환불의 예약. 상태와 관계없이 찾는다. */
  Optional<Reservation> find(UUID itemId, UUID refundId);

  /** 항목의 ACTIVE 예약(종류 무관). */
  Optional<Reservation> findActive(UUID itemId);

  /**
   * 항목이 WAITING_CONFIRMATION·PAYABLE이고 지급에 배정되지 않았을 때만 REFUND_RESERVED로 바꾸고 직전 상태와 순액으로 ACTIVE 환불
   * 예약을 만든다. 조건이 맞지 않으면 아무것도 바꾸지 않고 빈 값이다. 다른 트랜잭션이 잡은 항목 잠금은 1초까지만 기다린다.
   */
  Optional<Reservation> reserve(UUID itemId, UUID refundId, Instant now);

  /**
   * ACTIVE 환불 예약을 CONSUMED로, 그 항목을 REFUND_RESERVED에서 REFUNDED로 바꾸고 바뀐 항목 버전을 돌려준다. 둘 중 하나라도 조건이 맞지
   * 않으면 빈 값이며, 호출자는 트랜잭션을 롤백해야 한다.
   */
  Optional<Long> consume(UUID reservationId, Instant now);

  record ItemState(
      UUID id,
      SettlementItemStatus status,
      long externalAmount,
      long mileageAmount,
      long netAmount,
      UUID payoutId,
      long version) {}

  /** {@code itemVersion}은 조회·변경 시점 항목 버전이다. 발행 이벤트의 집계 버전으로 쓴다. */
  record Reservation(
      UUID id,
      UUID itemId,
      UUID refundId,
      String kind,
      long amount,
      SettlementItemStatus previousStatus,
      String status,
      long itemVersion) {}
}
