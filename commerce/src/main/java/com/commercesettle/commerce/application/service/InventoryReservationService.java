package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.model.InventoryOperation;
import com.commercesettle.commerce.domain.model.InventoryReservation;
import com.commercesettle.commerce.domain.model.InventoryReservation.Outcome;
import com.commercesettle.commerce.domain.model.InventoryReservation.Result;
import com.commercesettle.commerce.domain.model.Product;
import com.commercesettle.commerce.domain.model.ProductInventory;
import com.commercesettle.commerce.domain.repository.InventoryMovementRepository;
import com.commercesettle.commerce.domain.repository.InventoryReservationRepository;
import com.commercesettle.commerce.domain.repository.ProductInventoryRepository;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 흐름이 호출하는 일반 주문 재고 예약·확정·해제·복원. 모든 메서드는 호출한 주문 트랜잭션에 참여하고 트랜잭션 밖 호출은 거절하며 따로 커밋하지 않는다. 그래서 예약
 * 변경은 주문 상태·Outbox와 함께 커밋되거나 롤백된다.
 *
 * <p>상품 변경·판매자 조정과 같은 순서로 재고 행을 먼저 잠근 뒤 예약 행을 읽는다. 예약의 상품은 바뀌지 않으므로 잠금 전에 상품 ID만 읽어 잠글 행을 찾는다.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryReservationService {
  private final ProductRepository products;
  private final ProductInventoryRepository inventories;
  private final InventoryReservationRepository reservations;
  private final InventoryMovementRepository movements;
  private final Clock clock;

  public InventoryReservationService(
      ProductRepository products,
      ProductInventoryRepository inventories,
      InventoryReservationRepository reservations,
      InventoryMovementRepository movements,
      Clock clock) {
    this.products = products;
    this.inventories = inventories;
    this.reservations = reservations;
    this.movements = movements;
    this.clock = clock;
  }

  /**
   * product_inventory 행을 배타 잠금한 뒤 상품을 읽는다. 잠금은 호출자의 커밋·롤백까지 유지된다. 판매 가능 여부·가격·버전 검증은 주문 응용 서비스가 맡고
   * 반환된 상품은 변경하지 않는다. 상품이 없으면 {@link ProductNotFoundException}으로 거절한다.
   */
  public Product lockInventoryAndReadProduct(UUID productId) {
    lockInventory(productId);
    return products.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
  }

  /**
   * 주문 생성 때 수량을 예약한다. 가용 수량이 부족하면 InsufficientInventoryException으로 거절한다. 같은 주문·상품·수량·회차의 재처리는 기존
   * 예약을 그대로 두고 ALREADY_APPLIED를 반환한다.
   */
  public Outcome reserve(
      UUID orderId, UUID productId, int quantity, UUID cycleId, Instant expiresAt) {
    ProductInventory inventory = lockInventory(productId);
    Optional<InventoryReservation> existing = reservations.findByOrderId(orderId);
    if (existing.isPresent()) {
      if (!existing.get().isSameReservation(productId, quantity, cycleId)) {
        throw new IllegalStateException("order already has a different reservation: " + orderId);
      }
      return Outcome.ALREADY_APPLIED;
    }
    InventoryReservation.Created created =
        InventoryReservation.reserve(
            UUID.randomUUID(),
            orderId,
            inventory,
            quantity,
            cycleId,
            expiresAt,
            UUID.randomUUID(),
            clock.instant());
    movements.save(created.movement());
    inventories.save(inventory);
    reservations.save(created.reservation());
    return Outcome.APPLIED;
  }

  /** 확정 거절 뒤 재시도: 해제된 예약을 새 회차로 다시 확보한다. 가용 수량이 부족하면 거절하고 해제 상태를 유지한다. */
  public Outcome reserveAgain(UUID orderId, UUID newCycleId, Instant expiresAt) {
    return change(
        orderId,
        (reservation, inventory) ->
            reservation.reserveAgain(
                inventory, newCycleId, expiresAt, UUID.randomUUID(), clock.instant()));
  }

  /** 결제 확정 결과를 반영한다. 다른 회차의 결과는 STALE_CYCLE로 무시한다. */
  public Outcome consume(UUID orderId, UUID cycleId) {
    return change(
        orderId,
        (reservation, inventory) ->
            reservation.consume(inventory, cycleId, UUID.randomUUID(), clock.instant()));
  }

  /** 확정 거절 또는 Payment가 승인을 차단한 취소를 반영한다. 기한 경과만으로 호출하지 않는다. */
  public Outcome release(UUID orderId, UUID cycleId) {
    return change(
        orderId,
        (reservation, inventory) ->
            reservation.release(inventory, cycleId, UUID.randomUUID(), clock.instant()));
  }

  /** 미발송 주문의 환불 복구. 원장 상쇄 뒤 주문 복구 요청을 처리할 때 호출한다. */
  public Outcome restoreForRefund(UUID orderId, UUID refundId) {
    return restore(orderId, InventoryOperation.REFUND_RESTORE, refundId);
  }

  /** 발송 주문에서 재판매 가능으로 검수한 반품의 입고. 금융 환불 결과와 관계없이 호출한다. */
  public Outcome restoreForReturn(UUID orderId, UUID refundRequestId) {
    return restore(orderId, InventoryOperation.RETURN_RESTORE, refundRequestId);
  }

  private Outcome restore(UUID orderId, InventoryOperation operation, UUID businessRef) {
    return change(
        orderId,
        (reservation, inventory) ->
            reservation.restore(
                inventory, operation, businessRef, UUID.randomUUID(), clock.instant()));
  }

  private Outcome change(
      UUID orderId, BiFunction<InventoryReservation, ProductInventory, Result> transition) {
    UUID productId =
        reservations.findProductIdByOrderId(orderId).orElseThrow(() -> missingReservation(orderId));
    ProductInventory inventory = lockInventory(productId);
    InventoryReservation reservation =
        reservations.findByOrderId(orderId).orElseThrow(() -> missingReservation(orderId));
    Result result = transition.apply(reservation, inventory);
    if (result.outcome() == Outcome.APPLIED) {
      movements.save(result.movement());
      inventories.save(inventory);
      reservations.save(reservation);
    }
    return result.outcome();
  }

  private ProductInventory lockInventory(UUID productId) {
    return inventories
        .findByProductIdForUpdate(productId)
        .orElseThrow(() -> missingInventory(productId));
  }

  /** 재고 행이 없으면 상품도 없는지 확인해 없는 상품과 재고 행 누락을 구분한다. */
  private RuntimeException missingInventory(UUID productId) {
    if (products.findById(productId).isEmpty()) {
      return new ProductNotFoundException(productId);
    }
    return new IllegalStateException("inventory row is missing for product " + productId);
  }

  private static IllegalStateException missingReservation(UUID orderId) {
    return new IllegalStateException("inventory reservation is missing for order " + orderId);
  }
}
