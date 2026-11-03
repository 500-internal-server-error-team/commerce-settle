package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.domain.exception.CouponRejectedException;
import com.commercesettle.commerce.domain.exception.CouponRejectedException.Reason;
import com.commercesettle.commerce.domain.model.BuyerCoupon;
import com.commercesettle.commerce.domain.model.BuyerCouponStatus;
import com.commercesettle.commerce.domain.model.CouponFunder;
import com.commercesettle.commerce.domain.model.CouponHistory;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.repository.BuyerCouponRepository;
import com.commercesettle.commerce.domain.repository.CouponHistoryRepository;
import com.commercesettle.commerce.domain.repository.CouponTemplateRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 발급·조회와 주문의 예약·사용·해제·복구. 주문 경로의 변경은 주문 트랜잭션에만 참여해 주문 상태·Outbox와 함께 커밋·롤백된다. 쿠폰 행을 잠근 뒤 상태를 바꾸고,
 * 이력과 템플릿 집계를 같은 트랜잭션에 더한다(쿠폰 → 템플릿 순서).
 */
@Service
public class CouponService {
  private final CouponTemplateRepository templates;
  private final BuyerCouponRepository coupons;
  private final CouponHistoryRepository histories;
  private final Clock clock;

  public CouponService(
      CouponTemplateRepository templates,
      BuyerCouponRepository coupons,
      CouponHistoryRepository histories,
      Clock clock) {
    this.templates = templates;
    this.coupons = coupons;
    this.histories = histories;
    this.clock = clock;
  }

  /** 할인액과 부담자. */
  public record Discount(long amount, CouponFunder funder) {}

  /** 본인 쿠폰과 표시명·조회 시각의 상태. */
  public record CouponView(BuyerCoupon coupon, String name, BuyerCouponStatus status) {}

  /**
   * 가입 혜택 한 장을 발급한다. 가입 트랜잭션에 참여하며 같은 구매자의 재요청은 기존 쿠폰을 돌려준다. 활성 웰컴 캠페인이 없으면 서버 규칙으로 만든다. 가입 후 14일이
   * 지났으면 발급하지 않는다.
   */
  @Transactional
  public BuyerCoupon issueWelcome(UUID buyerId, Instant signedUpAt) {
    Optional<BuyerCoupon> existing = coupons.findWelcome(buyerId);
    if (existing.isPresent()) {
      return existing.get();
    }
    Instant now = clock.instant();
    CouponTemplate template = templates.findActiveWelcome().orElseGet(() -> createWelcome(now));
    BuyerCoupon.Issued issued =
        BuyerCoupon.issueWelcome(UUID.randomUUID(), template, buyerId, signedUpAt, now);
    BuyerCoupon coupon = coupons.save(issued.coupon());
    record(template.getId(), issued.history(), 1);
    return coupon;
  }

  private CouponTemplate createWelcome(Instant now) {
    templates.saveWelcomeIfAbsent(CouponTemplate.welcome(UUID.randomUUID(), now));
    return templates
        .findActiveWelcome()
        .orElseThrow(() -> new IllegalStateException("active welcome template is missing"));
  }

  @Transactional(readOnly = true)
  public PageResult<CouponView> buyerCoupons(UUID buyerId, int page, int size) {
    PageResult<BuyerCoupon> result = coupons.findByBuyer(buyerId, page, size);
    Map<UUID, String> names =
        templates.findNames(
            result.items().stream().map(BuyerCoupon::getTemplateId).collect(Collectors.toSet()));
    Instant now = clock.instant();
    return new PageResult<>(
        result.items().stream()
            .map(c -> new CouponView(c, names.get(c.getTemplateId()), c.statusAt(now)))
            .toList(),
        result.page(),
        result.size(),
        result.totalElements());
  }

  @Transactional(readOnly = true)
  public List<UUID> expiredCouponIds(Instant now, int limit) {
    return coupons.findExpiredIssuedIds(now, limit);
  }

  /** 기한이 지난 미사용 쿠폰 하나를 만료한다. 그사이 예약·만료됐으면 아무것도 바꾸지 않고 false다. */
  @Transactional
  public boolean expireIfDue(UUID couponId, Instant now) {
    BuyerCoupon coupon =
        coupons
            .findByIdForUpdate(couponId)
            .orElseThrow(() -> new IllegalStateException("coupon is missing: " + couponId));
    Optional<CouponHistory> history = coupon.expireIfDue(now);
    history.ifPresent(
        value -> {
          coupons.save(coupon);
          record(coupon.getTemplateId(), value, 0);
        });
    return history.isPresent();
  }

  /** 주문 견적의 할인액. 상태를 바꾸지 않으며 다른 구매자의 쿠폰은 쓸 수 없다. */
  public Discount quote(
      UUID buyerId,
      UUID couponId,
      UUID sellerId,
      UUID productId,
      long grossAmount,
      Order.ExternalMethod method,
      Instant now) {
    BuyerCoupon coupon = owned(coupons.findById(couponId), buyerId);
    long amount = coupon.discountFor(sellerId, productId, grossAmount, method, now);
    return new Discount(amount, coupon.getFunder());
  }

  /**
   * 주문 생성·재시도의 예약. 같은 쿠폰을 쓰는 다른 주문은 쿠폰 행 잠금 뒤 상태로 거절된다. 시각은 주문 트랜잭션이 정한 서버 시각을 받아 견적·주문 생성과 같은 기준으로
   * 기한을 판단한다.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reserve(Order order, Instant now) {
    change(
        order,
        coupon ->
            coupon.reserve(
                order.getId(),
                order.getPaymentCycleId(),
                order.getSellerId(),
                order.getProductId(),
                order.getAmounts().grossAmount(),
                order.getExternalMethod(),
                now));
  }

  /** 결제 확정. 업무 키는 원결제다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void use(Order order, Instant now) {
    change(order, coupon -> coupon.use(order.getId(), order.getPaymentId(), now));
  }

  /** 승인 차단된 취소의 해제. 업무 키는 결제 회차다. 확정 거절은 재시도를 위해 예약을 유지하므로 호출하지 않는다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void release(Order order, Instant now) {
    change(order, coupon -> coupon.release(order.getId(), order.getPaymentCycleId(), now));
  }

  /** 전액 환불의 원쿠폰 복구. 발송 여부와 관계없이 환불 완료 때 한 번 반영한다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void restore(Order order, Instant now) {
    change(order, coupon -> coupon.restore(order.getId(), order.getPaymentId(), now));
  }

  private void change(Order order, Function<BuyerCoupon, Optional<CouponHistory>> transition) {
    BuyerCoupon coupon =
        owned(coupons.findByIdForUpdate(order.getBuyerCouponId()), order.getBuyerId());
    Optional<CouponHistory> history = transition.apply(coupon);
    if (history.isPresent()) {
      coupons.save(coupon);
      record(coupon.getTemplateId(), history.get(), 0);
    }
  }

  private void record(UUID templateId, CouponHistory history, int issuedDelta) {
    histories.save(history);
    templates.addTotals(
        templateId, issuedDelta, history.getCommittedDelta(), history.getConsumedDelta());
  }

  private static BuyerCoupon owned(Optional<BuyerCoupon> coupon, UUID buyerId) {
    return coupon
        .filter(value -> value.isOwnedBy(buyerId))
        .orElseThrow(() -> new CouponRejectedException(Reason.NOT_OWNED));
  }
}
