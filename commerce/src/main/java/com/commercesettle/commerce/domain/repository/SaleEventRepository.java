package com.commercesettle.commerce.domain.repository;

import com.commercesettle.commerce.domain.model.SaleEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 목록은 createdAt DESC, id DESC 순서다. */
public interface SaleEventRepository {
  /** 변경을 즉시 DB에 반영해 버전을 갱신하고 동시 변경 충돌을 드러낸다. */
  SaleEvent save(SaleEvent event);

  Optional<SaleEvent> findById(UUID eventId);

  /** 재고 행을 잠그기 전에 잠글 상품을 찾는다. 이벤트 행은 읽어 두지 않는다. */
  Optional<UUID> findProductIdById(UUID eventId);

  /** 상품 재고 행을 잠근 뒤 이벤트 행을 쓰기 잠금으로 읽는다(재고 행 → 이벤트 행 순서). */
  Optional<SaleEvent> findByIdForUpdate(UUID eventId);

  /**
   * 선착순 참여의 수량 확보. 상품·재고 행을 잠그지 않고 열린 기간에 남은 수량이 있을 때만 R을 1 늘리는 조건부 갱신 한 번으로 처리한다. 갱신하지 못하면 false다.
   * 버전은 올리지 않는다. 이 트랜잭션에서 이미 읽은 이벤트 엔티티에는 반영되지 않는다.
   */
  boolean reserveSlot(UUID eventId, Instant now);

  /** 공개 후 종료 전(SCHEDULED·OPEN) 이벤트가 있는지. */
  boolean existsInProgressByProductId(UUID productId);

  /** 같은 상품에서 기간이 겹치는 공개 이벤트(SCHEDULED·OPEN·SOLD_OUT)가 있는지. 조기 종료한 이벤트는 제외한다. */
  boolean existsPublishedOverlap(
      UUID productId, UUID exceptEventId, Instant startsAt, Instant endsAt);

  PageResult<SaleEvent> findBySeller(UUID sellerId, int page, int size);

  /** 공개 목록에 보이는 예정·진행 중(SCHEDULED/OPEN) 이벤트. 종료·매진 이벤트는 단건·게이지 조회로만 제공한다. */
  PageResult<SaleEvent> findPublished(int page, int size);

  /** 시작 시각이 된 SCHEDULED 이벤트를 쓰기 잠금으로 읽는다. */
  List<SaleEvent> findStartedForUpdate(Instant now, int limit);

  /** 종료 시각이 지난 SCHEDULED·OPEN 이벤트 ID. 종료 시각이 이른 순서다. */
  List<UUID> findEndedIds(Instant now, int limit);
}
