package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.port.OrderBenefits;
import com.commercesettle.commerce.domain.exception.SaleEventNotFoundException;
import com.commercesettle.commerce.domain.model.CouponFunder;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.domain.repository.SaleEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 주문 흐름의 쿠폰·이벤트 포트 구현. 선착순 이벤트는 공개 때 고정한 가격으로 견적하고, 참여 수량은 {@link InventoryReservationService}의 이벤트
 * 경로로 주문 트랜잭션 안에서 예약·확정·해제·복원한다. 쿠폰은 이벤트 가격을 반영한 상품대금으로 할인액·부담자를 정하고 {@link CouponService}로
 * 예약·사용·해제·복구한다.
 */
@Service
public class OrderBenefitsService implements OrderBenefits {
  private final SaleEventRepository events;
  private final InventoryReservationService inventory;
  private final CouponService coupons;

  public OrderBenefitsService(
      SaleEventRepository events, InventoryReservationService inventory, CouponService coupons) {
    this.events = events;
    this.inventory = inventory;
    this.coupons = coupons;
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
    long price = unitPrice;
    Long eventVersion = null;
    if (eventId != null) {
      SaleEvent event =
          events
              .findById(eventId)
              .filter(SaleEvent::isPublished)
              .filter(value -> value.getProductId().equals(productId))
              .orElseThrow(() -> new SaleEventNotFoundException(eventId));
      price = event.getEventPrice();
      eventVersion = event.getVersion();
    }
    if (couponId == null) {
      return new Offer(price, eventVersion, 0, 0);
    }
    CouponService.Discount discount =
        coupons.quote(
            buyerId,
            couponId,
            sellerId,
            productId,
            Math.multiplyExact(price, quantity),
            method,
            now);
    return discount.funder() == CouponFunder.SELLER
        ? new Offer(price, eventVersion, discount.amount(), 0)
        : new Offer(price, eventVersion, 0, discount.amount());
  }

  /** 이벤트 가격·버전은 같은 트랜잭션의 견적에서 확인했고 공개 후 바뀌지 않으므로, 여기서는 참여 기간·남은 수량·참여 한도만 검사한다. */
  @Override
  public void reserve(Order order, Long expectedEventVersion, Instant now) {
    if (order.getBuyerCouponId() != null) {
      coupons.reserve(order, now);
    }
    if (order.getEventId() != null) {
      inventory.reserveForEvent(
          order.getId(),
          order.getEventId(),
          order.getBuyerId(),
          order.getPaymentCycleId(),
          order.getPaymentWaitUntil());
    }
  }

  /** 확정 거절 뒤에도 쿠폰 예약은 이 주문에 남아 있으므로 다시 반영하지 않는다. 이벤트 재고만 새 회차로 다시 잡는다. */
  @Override
  public void retry(Order order, Instant now) {
    if (order.getBuyerCouponId() != null) {
      coupons.reserve(order, now);
    }
    if (order.getEventId() != null) {
      inventory.reserveAgain(order.getId(), order.getPaymentCycleId(), order.getPaymentWaitUntil());
    }
  }

  @Override
  public void consume(Order order, Instant now) {
    if (order.getBuyerCouponId() != null) {
      coupons.use(order, now);
    }
    if (order.getEventId() != null) {
      inventory.consume(order.getId(), order.getPaymentCycleId());
    }
  }

  /**
   * 확정 거절은 재시도 기한까지 쿠폰 예약을 유지해 만료 전에 예약한 조건을 보존한다. 쿠폰은 승인 차단 취소가 확정될 때만 해제하고, 이벤트 재고는 거절 때 해제한 뒤
   * 재시도에서 다시 잡는다.
   */
  @Override
  public void release(Order order, Instant now) {
    if (order.getBuyerCouponId() != null && order.getStatus() != Order.Status.DECLINED) {
      coupons.release(order, now);
    }
    if (order.getEventId() != null) {
      inventory.release(order.getId(), order.getPaymentCycleId());
    }
  }

  /** 원쿠폰은 전액 환불 때 복구한다. 재고는 미발송 주문만 환불 복구에서 돌리고 발송한 상품은 반품 입고에서만 돌린다. */
  @Override
  public void restore(Order order, UUID refundId, Instant now) {
    if (order.getBuyerCouponId() != null) {
      coupons.restore(order, now);
    }
    if (order.getEventId() != null
        && order.getFulfillmentStatus() == Order.FulfillmentStatus.UNSHIPPED) {
      inventory.restoreForRefund(order.getId(), refundId);
    }
  }

  @Override
  public void receiveReturn(Order order, UUID requestId, boolean resalable, Instant now) {
    if (order.getEventId() == null) {
      return;
    }
    if (resalable) {
      inventory.restoreForReturn(order.getId(), requestId);
    } else {
      inventory.disposeForReturn(order.getId(), requestId);
    }
  }
}
