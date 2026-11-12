package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.Order;
import java.time.Instant;
import java.util.UUID;

/** 쿠폰·이벤트 소유 구현의 로컬 트랜잭션 계약. 조회는 예약하지 않는다. */
public interface OrderBenefits {
  Offer quote(
      UUID buyerId,
      UUID productId,
      UUID sellerId,
      long unitPrice,
      int quantity,
      UUID couponId,
      UUID eventId,
      Order.ExternalMethod method,
      Instant now);

  /**
   * 주문 트랜잭션의 견적. {@link #quote}와 같은 값을 돌려주되 쿠폰 행을 잠그고 읽어, 이어지는 {@link #reserve}가 같은 잠금과 최신 쿠폰 상태를
   * 쓰게 한다. 예약은 하지 않는다.
   */
  default Offer quoteForOrder(
      UUID buyerId,
      UUID productId,
      UUID sellerId,
      long unitPrice,
      int quantity,
      UUID couponId,
      UUID eventId,
      Order.ExternalMethod method,
      Instant now) {
    return quote(buyerId, productId, sellerId, unitPrice, quantity, couponId, eventId, method, now);
  }

  /** 주문·주소 사본을 만들기 전에 커밋된 이벤트 상태만 읽어 신청할 수 없는 이벤트를 거절한다. 잠금을 잡지 않으며 수량 확보는 reserve가 결정한다. */
  void requireEventOpen(UUID eventId);

  /**
   * 주문 트랜잭션에 참여하며 같은 트랜잭션의 {@link #quoteForOrder} 뒤에 호출한다. 이벤트 가격/버전·기간·참여 한도와 쿠폰 소유/수단/기한/예산을
   * 재검사한다.
   */
  void reserve(Order order, Long expectedEventVersion, Instant now);

  void retry(Order order, Instant now);

  void consume(Order order, Instant now);

  void release(Order order, Instant now);

  /** 원쿠폰과 미발송 이벤트 재고를 복원한다. 발송 상품은 입고에서만 복원한다. */
  void restore(Order order, UUID refundId, Instant now);

  /** 반품 입고의 이벤트 재고. 재판매 가능이면 가용으로 복원하고, 불가면 판매 수량만 줄인다. */
  void receiveReturn(Order order, UUID requestId, boolean resalable, Instant now);

  record Offer(
      long unitPrice, Long eventVersion, long sellerCouponAmount, long platformCouponAmount) {}
}
