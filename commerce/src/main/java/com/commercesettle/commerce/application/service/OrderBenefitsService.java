package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.exception.OrderDependencyUnavailableException;
import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.domain.exception.SaleEventNotFoundException;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.repository.SaleEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 주문 흐름의 쿠폰·이벤트 포트 구현. 선착순 이벤트는 공개 때 고정한 가격으로 견적하고, 참여 수량은 {@link InventoryReservationService}의 이벤트
 * 경로로 주문 트랜잭션 안에서 예약·확정·해제·복원한다. 쿠폰은 아직 구현 전이라 쿠폰을 쓴 요청은 연동 불가로 거절한다.
 */
@Service
public class OrderBenefitsService implements OrderBenefits {
  private final SaleEventRepository events;
  private final InventoryReservationService inventory;

  public OrderBenefitsService(SaleEventRepository events, InventoryReservationService inventory) {
    this.events = events;
    this.inventory = inventory;
  }

  /** 조회만 하며 참여 가능 여부는 예약 때 판단한다. 이벤트 버전은 판매·예약 수량 변경으로는 바뀌지 않는다. */
  @Override
  public Offer quote(
      UUID buyerId,
      UUID productId,
      UUID sellerId,
      long unitPrice,
      int quantity,
      UUID couponId,
      UUID eventId,
      Order.ExternalMethod method,
      Instant now) {
    requireNoCoupon(couponId);
    if (eventId == null) {
      return new Offer(unitPrice, null, 0, 0);
    }
    SaleEvent event =
        events
            .findById(eventId)
            .filter(SaleEvent::isPublished)
            .filter(value -> value.getProductId().equals(productId))
            .orElseThrow(() -> new SaleEventNotFoundException(eventId));
    return new Offer(event.getEventPrice(), event.getVersion(), 0, 0);
  }

  /** 이벤트 가격·버전은 같은 트랜잭션의 견적에서 확인했고 공개 후 바뀌지 않으므로, 여기서는 참여 기간·남은 수량·참여 한도만 검사한다. */
  @Override
  public void reserve(Order order, Long expectedEventVersion, Instant now) {
    requireNoCoupon(order.getBuyerCouponId());
    if (order.getEventId() != null) {
      inventory.reserveForEvent(
          order.getId(),
          order.getEventId(),
          order.getBuyerId(),
          order.getPaymentCycleId(),
          order.getPaymentWaitUntil());
    }
  }

  @Override
  public void retry(Order order, Instant now) {
    requireNoCoupon(order.getBuyerCouponId());
    if (order.getEventId() != null) {
      inventory.reserveAgain(order.getId(), order.getPaymentCycleId(), order.getPaymentWaitUntil());
    }
  }

  @Override
  public void consume(Order order, Instant now) {
    requireNoCoupon(order.getBuyerCouponId());
    if (order.getEventId() != null) {
      inventory.consume(order.getId(), order.getPaymentCycleId());
    }
  }

  @Override
  public void release(Order order, Instant now) {
    requireNoCoupon(order.getBuyerCouponId());
    if (order.getEventId() != null) {
      inventory.release(order.getId(), order.getPaymentCycleId());
    }
  }

  /** 미발송 주문만 환불 복구에서 재고를 돌린다. 발송한 상품은 반품 입고에서만 돌린다. */
  @Override
  public void restore(Order order, UUID refundId, Instant now) {
    requireNoCoupon(order.getBuyerCouponId());
    if (order.getEventId() != null
        && order.getFulfillmentStatus() == Order.FulfillmentStatus.UNSHIPPED) {
      inventory.restoreForRefund(order.getId(), refundId);
    }
  }

  @Override
  public void receiveReturn(Order order, UUID requestId, Instant now) {
    requireNoCoupon(order.getBuyerCouponId());
    if (order.getEventId() != null) {
      inventory.restoreForReturn(order.getId(), requestId);
    }
  }

  // TODO: 쿠폰 구현 후 쿠폰 예약·사용·복구를 연결한다.
  private static void requireNoCoupon(UUID couponId) {
    if (couponId != null) {
      throw new OrderDependencyUnavailableException();
    }
  }
}
