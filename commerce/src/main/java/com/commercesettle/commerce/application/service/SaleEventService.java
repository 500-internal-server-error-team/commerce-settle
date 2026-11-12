package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException;
import com.commercesettle.commerce.domain.exception.SaleEventConflictException.Reason;
import com.commercesettle.commerce.domain.exception.SaleEventNotFoundException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import com.commercesettle.commerce.domain.model.InventoryMovement;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.model.ProductStatus;
import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.repository.InventoryMovementRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.domain.repository.ProductInventoryRepository;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import com.commercesettle.commerce.domain.repository.SaleEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선착순 이벤트 등록·공개·종료와 조회. 판매자 작업은 이벤트·상품 소유자를 대조하며 타인 이벤트는 없는 이벤트와 같게 취급한다. 쓰기는 멱등 기록·업무 변경을 한 트랜잭션에
 * 저장한다.
 *
 * <p>공개·종료는 재고 수량을 바꾸므로 상품 변경·판매자 조정과 같은 순서로 product_inventory 행을 먼저 잠근 뒤 이벤트 행을 잠근다. 같은 상품의 기간 중복
 * 검사도 이 잠금 아래에서 해 동시 공개가 둘 다 통과하지 않는다.
 */
@Service
public class SaleEventService {
  private static final String SELLER = "SELLER";

  private final SaleEventRepository events;
  private final ProductRepository products;
  private final ProductInventoryRepository inventories;
  private final InventoryMovementRepository movements;
  private final IdempotentRequests idempotency;
  private final Clock clock;

  public SaleEventService(
      SaleEventRepository events,
      ProductRepository products,
      ProductInventoryRepository inventories,
      InventoryMovementRepository movements,
      IdempotentRequests idempotency,
      Clock clock) {
    this.events = events;
    this.products = products;
    this.inventories = inventories;
    this.movements = movements;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResult<SaleEvent> sellerEvents(UUID sellerId, int page, int size) {
    return events.findBySeller(sellerId, page, size);
  }

  @Transactional(readOnly = true)
  public SaleEvent sellerEvent(UUID sellerId, UUID eventId) {
    return ownedEvent(sellerId, eventId);
  }

  @Transactional(readOnly = true)
  public PageResult<SaleEvent> publishedEvents(int page, int size) {
    return events.findPublished(page, size);
  }

  @Transactional(readOnly = true)
  public SaleEvent publishedEvent(UUID eventId) {
    return events
        .findById(eventId)
        .filter(SaleEvent::isPublished)
        .orElseThrow(() -> new SaleEventNotFoundException(eventId));
  }

  /** DB의 현재 값과 서버 시각으로 계산한다. 조회 시각을 asOf로 함께 돌려준다. */
  @Transactional(readOnly = true)
  public GaugeView gauge(UUID eventId) {
    SaleEvent event = publishedEvent(eventId);
    Instant now = clock.instant();
    return new GaugeView(event.getId(), event.gauge(now), now);
  }

  /** 미공개 이벤트를 만든다. 재고는 공개 때 배정한다. */
  @Transactional
  public SaleEvent register(
      UUID sellerId,
      String idempotencyKey,
      UUID productId,
      String name,
      long eventPrice,
      long quota,
      Instant startsAt,
      Instant endsAt) {
    UUID eventId = UUID.randomUUID();
    return idempotency.execute(
        sellerRequest(
            sellerId,
            "EVENT_REGISTER",
            idempotencyKey,
            List.of(productId, name, eventPrice, quota, startsAt, endsAt)),
        eventId,
        201,
        recordId -> {
          ownedProduct(sellerId, productId);
          return events.save(
              SaleEvent.register(
                  eventId,
                  sellerId,
                  productId,
                  name,
                  eventPrice,
                  quota,
                  startsAt,
                  endsAt,
                  clock.instant()));
        },
        existingId -> ownedEvent(sellerId, existingId));
  }

  /** 미공개 이벤트의 전체 변경. 재고를 배정하지 않은 상태라 재고 행을 잠그지 않는다. */
  @Transactional
  public SaleEvent change(
      UUID sellerId,
      UUID eventId,
      String idempotencyKey,
      UUID productId,
      String name,
      long eventPrice,
      long quota,
      Instant startsAt,
      Instant endsAt,
      long expectedVersion) {
    return idempotency.execute(
        sellerRequest(
            sellerId,
            "EVENT_CHANGE",
            idempotencyKey,
            List.of(
                eventId, productId, name, eventPrice, quota, startsAt, endsAt, expectedVersion)),
        eventId,
        200,
        recordId -> {
          SaleEvent event = ownedEvent(sellerId, eventId);
          ownedProduct(sellerId, productId);
          event.change(
              productId,
              name,
              eventPrice,
              quota,
              startsAt,
              endsAt,
              expectedVersion,
              clock.instant());
          return events.save(event);
        },
        existingId -> ownedEvent(sellerId, existingId));
  }

  /** 판매 중인 상품이고 같은 상품에 기간이 겹치는 공개 이벤트가 없을 때 한정 수량을 배정해 공개한다. */
  @Transactional
  public SaleEvent publish(
      UUID sellerId, UUID eventId, String idempotencyKey, long expectedVersion) {
    return idempotency.execute(
        sellerRequest(sellerId, "EVENT_PUBLISH", idempotencyKey, List.of(eventId, expectedVersion)),
        eventId,
        200,
        recordId -> {
          Locked locked = lock(sellerId, eventId, expectedVersion);
          SaleEvent event = locked.event();
          Product product = ownedProduct(sellerId, event.getProductId());
          if (product.getStatus() != ProductStatus.ON_SALE) {
            throw new SaleEventConflictException(Reason.PRODUCT_NOT_ON_SALE);
          }
          if (events.existsPublishedOverlap(
              event.getProductId(), eventId, event.getStartsAt(), event.getEndsAt())) {
            throw new SaleEventConflictException(Reason.PERIOD_OVERLAP);
          }
          InventoryMovement movement =
              event.publish(
                  locked.inventory(), expectedVersion, UUID.randomUUID(), clock.instant());
          return save(locked, movement);
        },
        existingId -> ownedEvent(sellerId, existingId));
  }

  /** 조기 종료. 신규 참여를 막고 판매·예약되지 않은 배정분만 가용 재고로 돌린다. */
  @Transactional
  public SaleEvent close(
      UUID sellerId, UUID eventId, String idempotencyKey, long expectedVersion, String reason) {
    return idempotency.execute(
        sellerRequest(
            sellerId, "EVENT_CLOSE", idempotencyKey, List.of(eventId, expectedVersion, reason)),
        eventId,
        200,
        recordId -> {
          Locked locked = lock(sellerId, eventId, expectedVersion);
          InventoryMovement movement =
              locked
                  .event()
                  .close(
                      locked.inventory(),
                      expectedVersion,
                      reason,
                      UUID.randomUUID(),
                      clock.instant());
          return save(locked, movement);
        },
        existingId -> ownedEvent(sellerId, existingId));
  }

  /**
   * 시작 시각이 된 예정 이벤트를 연다. 재고는 바꾸지 않으므로 이벤트 행만 잠그며, 참여·종료가 잡고 있는 행은 기다리지 않고 다음 실행으로 미룬다. 신청은 서버 시각으로
   * 판단하므로 열기가 늦어도 참여를 막지 않는다.
   */
  @Transactional
  public int openStarted(Instant now, int limit) {
    int opened = 0;
    for (SaleEvent event : events.findStartedForUpdate(now, limit)) {
      if (event.openIfStarted(now)) {
        events.save(event);
        opened++;
      }
    }
    return opened;
  }

  @Transactional(readOnly = true)
  public List<UUID> endedEventIds(Instant now, int limit) {
    return events.findEndedIds(now, limit);
  }

  /** 종료 시각이 지난 이벤트 하나를 닫는다. 이미 닫혔으면 아무것도 바꾸지 않고 false다. */
  @Transactional
  public boolean closeIfEnded(UUID eventId, Instant now) {
    Optional<UUID> productId = events.findProductIdById(eventId);
    if (productId.isEmpty()) {
      return false;
    }
    ProductInventory inventory = lockInventory(productId.get());
    SaleEvent event =
        events
            .findByIdForUpdate(eventId)
            .orElseThrow(() -> new SaleEventNotFoundException(eventId));
    Optional<InventoryMovement> movement = event.closeIfEnded(inventory, UUID.randomUUID(), now);
    movement.ifPresent(value -> save(new Locked(inventory, event), value));
    return movement.isPresent();
  }

  private SaleEvent save(Locked locked, InventoryMovement movement) {
    movements.save(movement);
    inventories.save(locked.inventory());
    return events.save(locked.event());
  }

  /**
   * 잠금 전에 상품 ID만 읽어 잠글 재고 행을 찾는다. 미공개 이벤트는 그 사이 변경으로 상품이 바뀔 수 있으므로, 잠근 이벤트의 상품이 잠근 재고 행과 다르면 버전 충돌로
   * 거절해 최신 이벤트로 다시 요청하게 한다.
   */
  private Locked lock(UUID sellerId, UUID eventId, long expectedVersion) {
    UUID productId =
        events
            .findProductIdById(eventId)
            .orElseThrow(() -> new SaleEventNotFoundException(eventId));
    ProductInventory inventory = lockInventory(productId);
    SaleEvent event =
        events
            .findByIdForUpdate(eventId)
            .filter(value -> value.isOwnedBy(sellerId))
            .orElseThrow(() -> new SaleEventNotFoundException(eventId));
    if (!event.getProductId().equals(productId)) {
      throw new VersionConflictException("sale event", expectedVersion, event.getVersion());
    }
    return new Locked(inventory, event);
  }

  private ProductInventory lockInventory(UUID productId) {
    return inventories
        .findByProductIdForUpdate(productId)
        .orElseThrow(
            () -> new IllegalStateException("inventory row is missing for product " + productId));
  }

  private SaleEvent ownedEvent(UUID sellerId, UUID eventId) {
    return events
        .findById(eventId)
        .filter(event -> event.isOwnedBy(sellerId))
        .orElseThrow(() -> new SaleEventNotFoundException(eventId));
  }

  private Product ownedProduct(UUID sellerId, UUID productId) {
    return products
        .findById(productId)
        .filter(product -> !product.isDeleted() && product.isOwnedBy(sellerId))
        .orElseThrow(() -> new ProductNotFoundException(productId));
  }

  private static IdempotentRequests.Request sellerRequest(
      UUID sellerId, String operation, String idempotencyKey, List<?> body) {
    return new IdempotentRequests.Request(
        SELLER, sellerId.toString(), operation, idempotencyKey, body);
  }

  /** 공개 이벤트의 게이지와 계산 시각. */
  public record GaugeView(UUID eventId, SaleEvent.Gauge gauge, Instant asOf) {}

  private record Locked(ProductInventory inventory, SaleEvent event) {}
}
