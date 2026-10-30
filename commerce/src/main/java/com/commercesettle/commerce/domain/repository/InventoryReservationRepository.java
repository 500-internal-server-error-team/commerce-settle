package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.InventoryReservation;
import java.util.Optional;
import java.util.UUID;

public interface InventoryReservationRepository {
  /** 변경을 즉시 DB에 반영해 버전을 갱신하고 주문당 한 행 제약을 드러낸다. */
  InventoryReservation save(InventoryReservation reservation);

  Optional<InventoryReservation> findByOrderId(UUID orderId);

  /** 예약 엔티티를 불러오지 않고 상품 ID만 읽는다. 재고 행을 먼저 잠그기 위해 잠글 대상을 찾을 때 쓴다. */
  Optional<UUID> findProductIdByOrderId(UUID orderId);
}
