package com.commercesettle.commerce.domain.model;

import com.commercesettle.commerce.domain.exception.CouponRejectedException;
import com.commercesettle.commerce.domain.exception.CouponRejectedException.Reason;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 발급 당시 조건을 사본으로 보존한 구매자 쿠폰. 상태 전이와 그 이력·예산 증감을 한 메서드에서 만들어 둘이 어긋나지 않게 한다. 기한은 서버 시각의 {@code
 * validFrom ≤ 시각 < expiresAt}으로 판단한다. 예약 해제·환불 복구 때 기한이 지났으면 ISSUED가 아니라 EXPIRED로 두고 남은 약속액을 상쇄한다.
 */
@Entity
@Table(name = "buyer_coupons")
public class BuyerCoupon extends AuditableEntity {
  @Id private UUID id;
  private UUID templateId;
  private UUID buyerId;

  @Enumerated(EnumType.STRING)
  private CouponKind kind;

  @Enumerated(EnumType.STRING)
  private CouponFunder funder;

  private UUID sellerId;
  private UUID productId;
  private LocalDate targetMonth;
  private long discountAmount;
  private long minOrderAmount;

  @JdbcTypeCode(SqlTypes.ARRAY)
  private String[] allowedMethods;

  private Instant validFrom;
  private Instant expiresAt;

  @Enumerated(EnumType.STRING)
  private BuyerCouponStatus status;

  private UUID reservedOrderId;
  private UUID usedOrderId;

  @Version private Long version;

  /** 새 쿠폰과 발급 이력. */
  public record Issued(BuyerCoupon coupon, CouponHistory history) {}

  protected BuyerCoupon() {}

  private BuyerCoupon(
      UUID id,
      CouponTemplate template,
      UUID buyerId,
      Instant validFrom,
      Instant expiresAt,
      Instant now) {
    super(now);
    this.id = Objects.requireNonNull(id, "id");
    this.templateId = template.getId();
    this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    this.kind = template.getKind();
    this.funder = template.getFunder();
    this.sellerId = template.getSellerId();
    this.productId = template.getProductId();
    this.targetMonth = template.getTargetMonth();
    this.discountAmount = template.getDiscountAmount();
    this.minOrderAmount = template.getMinOrderAmount();
    this.allowedMethods =
        template.getAllowedMethods().stream().map(Enum::name).toArray(String[]::new);
    this.validFrom = truncate(validFrom);
    this.expiresAt = truncate(expiresAt);
    this.status = BuyerCouponStatus.ISSUED;
  }

  /** 가입 혜택 한 장. 기한은 가입 시각+14일이며 기한이 지난 늦은 발급은 하지 않는다. 발급 업무 키는 구매자 ID다. */
  public static Issued issueWelcome(
      UUID id, CouponTemplate template, UUID buyerId, Instant signedUpAt, Instant now) {
    if (template.getKind() != CouponKind.WELCOME || !template.isActive()) {
      throw new IllegalArgumentException("not an active welcome template: " + template.getId());
    }
    Instant expiresAt = signedUpAt.plus(CouponTemplate.WELCOME_VALIDITY);
    if (!now.isBefore(expiresAt)) {
      throw new CouponRejectedException(Reason.EXPIRED);
    }
    BuyerCoupon coupon = new BuyerCoupon(id, template, buyerId, signedUpAt, expiresAt, now);
    CouponHistory history =
        coupon.history(CouponOperation.ISSUE, null, buyerId, null, coupon.discountAmount, 0, now);
    return new Issued(coupon, history);
  }

  /** 주문에 쓸 할인액. 상태·기한·수단·판매자/상품·최소 주문금액을 확인하며 상태를 바꾸지 않는다. grossAmount는 쿠폰 적용 전 상품대금이다. */
  public long discountFor(
      UUID sellerId, UUID productId, long grossAmount, Order.ExternalMethod method, Instant now) {
    if (status != BuyerCouponStatus.ISSUED) {
      throw new CouponRejectedException(
          status == BuyerCouponStatus.EXPIRED ? Reason.EXPIRED : Reason.NOT_AVAILABLE);
    }
    requireUsableAt(now);
    if (method == null || !Arrays.asList(allowedMethods).contains(method.name())) {
      throw new CouponRejectedException(Reason.METHOD_NOT_ALLOWED);
    }
    if (kind == CouponKind.SELLER
        && (!this.sellerId.equals(sellerId)
            || (this.productId != null && !this.productId.equals(productId)))) {
      throw new CouponRejectedException(Reason.PRODUCT_MISMATCH);
    }
    if (grossAmount < minOrderAmount) {
      throw new CouponRejectedException(Reason.BELOW_MIN_ORDER);
    }
    return discountAmount;
  }

  /** 주문 생성·확정 거절 뒤 재시도의 예약. 같은 주문에 이미 예약돼 있으면 다시 반영하지 않는다. 예약 업무 키는 결제 회차다. */
  public Optional<CouponHistory> reserve(
      UUID orderId,
      UUID cycleId,
      UUID sellerId,
      UUID productId,
      long grossAmount,
      Order.ExternalMethod method,
      Instant now) {
    if (status == BuyerCouponStatus.RESERVED && orderId.equals(reservedOrderId)) {
      return Optional.empty();
    }
    discountFor(sellerId, productId, grossAmount, method, now);
    reservedOrderId = Objects.requireNonNull(orderId, "orderId");
    return Optional.of(
        moveTo(BuyerCouponStatus.RESERVED, CouponOperation.RESERVE, orderId, cycleId, 0, 0, now));
  }

  /**
   * 확정 거절·승인 차단된 취소의 해제. 기한 안이면 ISSUED로 돌리고, 지났으면 EXPIRED로 두며 약속액을 상쇄한다. 이 주문의 예약이 아니면 반영하지 않는다.
   */
  public Optional<CouponHistory> release(UUID orderId, UUID cycleId, Instant now) {
    if (status != BuyerCouponStatus.RESERVED || !orderId.equals(reservedOrderId)) {
      return Optional.empty();
    }
    reservedOrderId = null;
    return Optional.of(returnUnused(CouponOperation.RELEASE, orderId, cycleId, 0, now));
  }

  /** 결제 확정: 약속액을 사용액으로 옮긴다. 업무 키는 원결제다. 이미 이 주문에 사용했으면 반영하지 않는다. */
  public Optional<CouponHistory> use(UUID orderId, UUID paymentId, Instant now) {
    if (status == BuyerCouponStatus.USED && orderId.equals(usedOrderId)) {
      return Optional.empty();
    }
    if (status != BuyerCouponStatus.RESERVED || !orderId.equals(reservedOrderId)) {
      throw new IllegalStateException("coupon " + id + " is not reserved for order " + orderId);
    }
    reservedOrderId = null;
    usedOrderId = orderId;
    return Optional.of(
        moveTo(
            BuyerCouponStatus.USED,
            CouponOperation.USE,
            orderId,
            paymentId,
            -discountAmount,
            discountAmount,
            now));
  }

  /**
   * 전액 환불의 원쿠폰 복구. 사용액을 상쇄하고 기한 안이면 약속액으로 되돌린다. 기한이 지났으면 EXPIRED로 두고 새 쿠폰이나 기한 연장은 하지 않는다. 이 주문에
   * 사용한 쿠폰이 아니면(이미 복구했으면) 반영하지 않는다.
   */
  public Optional<CouponHistory> restore(UUID orderId, UUID paymentId, Instant now) {
    if (status != BuyerCouponStatus.USED || !orderId.equals(usedOrderId)) {
      return Optional.empty();
    }
    usedOrderId = null;
    return Optional.of(
        returnUnused(CouponOperation.RESTORE, orderId, paymentId, -discountAmount, now));
  }

  /**
   * 기한이 지난 미사용 쿠폰을 만료하고 남은 약속액을 상쇄한다. 업무 키는 쿠폰 ID라 재실행해도 한 번만 남는다. 예약 중이거나 사용한 쿠폰은 대상이 아니며, 예약 중
   * 기한이 지난 쿠폰은 안전한 해제가 확인될 때 해제와 함께 만료한다.
   */
  public Optional<CouponHistory> expireIfDue(Instant now) {
    if (status != BuyerCouponStatus.ISSUED || now.isBefore(expiresAt)) {
      return Optional.empty();
    }
    return Optional.of(
        moveTo(
            BuyerCouponStatus.EXPIRED, CouponOperation.EXPIRE, null, id, -discountAmount, 0, now));
  }

  /** 사용하지 않은 할인으로 돌린다. 기한이 지났으면 약속액까지 상쇄해 EXPIRED로 둔다. */
  private CouponHistory returnUnused(
      CouponOperation operation, UUID orderId, UUID businessRef, long consumedDelta, Instant now) {
    boolean expired = !now.isBefore(expiresAt);
    long committedDelta = (consumedDelta < 0 ? discountAmount : 0) - (expired ? discountAmount : 0);
    return moveTo(
        expired ? BuyerCouponStatus.EXPIRED : BuyerCouponStatus.ISSUED,
        operation,
        orderId,
        businessRef,
        committedDelta,
        consumedDelta,
        now);
  }

  private void requireUsableAt(Instant now) {
    if (now.isBefore(validFrom)) {
      throw new CouponRejectedException(Reason.NOT_STARTED);
    }
    if (!now.isBefore(expiresAt)) {
      throw new CouponRejectedException(Reason.EXPIRED);
    }
  }

  private CouponHistory moveTo(
      BuyerCouponStatus next,
      CouponOperation operation,
      UUID orderId,
      UUID businessRef,
      long committedDelta,
      long consumedDelta,
      Instant now) {
    BuyerCouponStatus from = status;
    status = next;
    touch(now);
    return history(operation, from, businessRef, orderId, committedDelta, consumedDelta, now);
  }

  private CouponHistory history(
      CouponOperation operation,
      BuyerCouponStatus from,
      UUID businessRef,
      UUID orderId,
      long committedDelta,
      long consumedDelta,
      Instant now) {
    return CouponHistory.of(
        id, orderId, operation, businessRef, from, status, committedDelta, consumedDelta, now);
  }

  /** 기한이 지났지만 아직 만료 처리하지 않은 미사용 쿠폰도 만료로 보여 준다. */
  public BuyerCouponStatus statusAt(Instant now) {
    return status == BuyerCouponStatus.ISSUED && !now.isBefore(expiresAt)
        ? BuyerCouponStatus.EXPIRED
        : status;
  }

  public boolean isOwnedBy(UUID buyerId) {
    return this.buyerId.equals(buyerId);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTemplateId() {
    return templateId;
  }

  public UUID getBuyerId() {
    return buyerId;
  }

  public CouponKind getKind() {
    return kind;
  }

  public CouponFunder getFunder() {
    return funder;
  }

  public UUID getSellerId() {
    return sellerId;
  }

  public UUID getProductId() {
    return productId;
  }

  public LocalDate getTargetMonth() {
    return targetMonth;
  }

  public long getDiscountAmount() {
    return discountAmount;
  }

  public long getMinOrderAmount() {
    return minOrderAmount;
  }

  public List<Order.ExternalMethod> getAllowedMethods() {
    return Arrays.stream(allowedMethods).map(Order.ExternalMethod::valueOf).toList();
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public BuyerCouponStatus getStatus() {
    return status;
  }

  public UUID getReservedOrderId() {
    return reservedOrderId;
  }

  public UUID getUsedOrderId() {
    return usedOrderId;
  }

  public long getVersion() {
    return version == null ? 0 : version;
  }
}
