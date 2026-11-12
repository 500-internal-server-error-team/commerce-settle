package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.CouponRejectedException;
import com.commercesettle.commerce.domain.exception.CouponRejectedException.Reason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 웰컴 쿠폰의 발급·예약·사용·해제·복구 전이와 그 이력의 예산 증감을 확인한다. */
class BuyerCouponTest {
  private static final Instant SIGNED_UP = Instant.parse("2026-11-02T01:00:00Z");
  private static final Instant NOW = SIGNED_UP.plus(Duration.ofDays(1));
  private static final Instant EXPIRES = SIGNED_UP.plus(Duration.ofDays(14));
  private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID CYCLE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  private final CouponTemplate template = CouponTemplate.welcome(UUID.randomUUID(), SIGNED_UP);
  private final BuyerCoupon.Issued issued =
      BuyerCoupon.issueWelcome(UUID.randomUUID(), template, BUYER, SIGNED_UP, NOW);
  private final BuyerCoupon coupon = issued.coupon();

  @Test
  @DisplayName("웰컴 쿠폰은 가입 후 14일 기한·플랫폼 부담으로 발급하고 할인액을 약속액으로 남긴다")
  void issuesWelcomeWithCommitment() {
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.ISSUED);
    assertThat(coupon.getExpiresAt()).isEqualTo(EXPIRES);
    assertThat(coupon.getFunder()).isEqualTo(CouponFunder.PLATFORM);
    assertThat(coupon.getDiscountAmount()).isEqualTo(3_000);
    assertThat(coupon.getAllowedMethods()).containsExactly(Order.ExternalMethod.values());
    assertHistory(issued.history(), CouponOperation.ISSUE, null, BUYER, 3_000, 0);
  }

  @Test
  @DisplayName("가입 후 14일이 지나면 웰컴 쿠폰을 발급하지 않는다")
  void rejectsLateWelcomeIssue() {
    assertThatThrownBy(
            () -> BuyerCoupon.issueWelcome(UUID.randomUUID(), template, BUYER, SIGNED_UP, EXPIRES))
        .extracting(e -> ((CouponRejectedException) e).reason())
        .isEqualTo(Reason.EXPIRED);
  }

  @Test
  @DisplayName("할인 판단은 기한·수단·최소 상품대금을 확인하고 상태를 바꾸지 않는다")
  void checksTermsWithoutChangingState() {
    assertThat(discount(20_000, NOW)).isEqualTo(3_000);
    assertRejected(() -> discount(19_999, NOW), Reason.BELOW_MIN_ORDER);
    assertRejected(() -> discount(20_000, EXPIRES), Reason.EXPIRED);
    assertRejected(() -> discount(20_000, SIGNED_UP.minusSeconds(1)), Reason.NOT_STARTED);
    assertRejected(
        () -> coupon.discountFor(SELLER, PRODUCT, 20_000, null, NOW), Reason.METHOD_NOT_ALLOWED);
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.ISSUED);
  }

  @Test
  @DisplayName("예약·사용은 약속액을 사용액으로 옮기고, 같은 주문의 재처리는 다시 반영하지 않는다")
  void reservesAndUsesOnce() {
    assertHistory(
        reserve(ORDER).orElseThrow(),
        CouponOperation.RESERVE,
        BuyerCouponStatus.ISSUED,
        CYCLE,
        0,
        0);
    assertThat(reserve(ORDER)).isEmpty();
    assertThat(coupon.getReservedOrderId()).isEqualTo(ORDER);

    assertHistory(
        coupon.use(ORDER, PAYMENT, NOW).orElseThrow(),
        CouponOperation.USE,
        BuyerCouponStatus.RESERVED,
        PAYMENT,
        -3_000,
        3_000);
    assertThat(coupon.use(ORDER, PAYMENT, NOW)).isEmpty();
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.USED);
    assertThat(coupon.getUsedOrderId()).isEqualTo(ORDER);
    assertThat(coupon.getReservedOrderId()).isNull();
  }

  @Test
  @DisplayName("다른 주문에 예약된 쿠폰은 쓸 수 없고, 그 주문의 해제만 반영한다")
  void keepsReservationOfAnotherOrder() {
    reserve(ORDER);

    assertRejected(() -> reserve(UUID.randomUUID()), Reason.NOT_AVAILABLE);
    assertThat(coupon.release(UUID.randomUUID(), CYCLE, NOW)).isEmpty();
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.RESERVED);
  }

  @Test
  @DisplayName("기한 안의 해제는 ISSUED로 돌리고, 기한이 지난 뒤 해제는 EXPIRED로 두며 약속액을 상쇄한다")
  void releasesByExpiry() {
    reserve(ORDER);
    assertHistory(
        coupon.release(ORDER, CYCLE, NOW).orElseThrow(),
        CouponOperation.RELEASE,
        BuyerCouponStatus.RESERVED,
        CYCLE,
        0,
        0);
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.ISSUED);
    assertThat(coupon.release(ORDER, CYCLE, NOW)).isEmpty();

    UUID next = UUID.randomUUID();
    coupon.reserve(next, CYCLE, SELLER, PRODUCT, 20_000, Order.ExternalMethod.CREDIT_CARD, NOW);
    assertHistory(
        coupon.release(next, CYCLE, EXPIRES).orElseThrow(),
        CouponOperation.RELEASE,
        BuyerCouponStatus.RESERVED,
        CYCLE,
        -3_000,
        0);
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.EXPIRED);
    assertRejected(() -> discount(20_000, NOW), Reason.EXPIRED);
  }

  @Test
  @DisplayName("환불 복구는 사용액을 상쇄하고 기한 안이면 약속액으로, 지났으면 EXPIRED로 둔다. 한 번만 반영한다")
  void restoresUsedCouponOnce() {
    BuyerCoupon other =
        BuyerCoupon.issueWelcome(UUID.randomUUID(), template, BUYER, SIGNED_UP, NOW).coupon();
    reserve(ORDER);
    coupon.use(ORDER, PAYMENT, NOW);
    UUID otherOrder = UUID.randomUUID();
    other.reserve(otherOrder, CYCLE, SELLER, PRODUCT, 20_000, Order.ExternalMethod.EASY_PAY, NOW);
    other.use(otherOrder, PAYMENT, NOW);

    assertHistory(
        coupon.restore(ORDER, PAYMENT, NOW).orElseThrow(),
        CouponOperation.RESTORE,
        BuyerCouponStatus.USED,
        PAYMENT,
        3_000,
        -3_000);
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.ISSUED);
    assertThat(coupon.restore(ORDER, PAYMENT, NOW)).isEmpty();
    assertHistory(
        other.restore(otherOrder, PAYMENT, EXPIRES).orElseThrow(),
        CouponOperation.RESTORE,
        BuyerCouponStatus.USED,
        PAYMENT,
        0,
        -3_000);
    assertThat(other.getStatus()).isEqualTo(BuyerCouponStatus.EXPIRED);
  }

  @Test
  @DisplayName("만료는 기한이 지난 미사용 쿠폰만 EXPIRED로 바꾸고 약속액을 한 번 상쇄하며, 예약 중인 쿠폰은 유지한다")
  void expiresOnlyUnusedDueCoupon() {
    assertThat(coupon.expireIfDue(EXPIRES.minusSeconds(1))).isEmpty();
    assertHistory(
        coupon.expireIfDue(EXPIRES).orElseThrow(),
        CouponOperation.EXPIRE,
        BuyerCouponStatus.ISSUED,
        coupon.getId(),
        -3_000,
        0);
    assertThat(coupon.getStatus()).isEqualTo(BuyerCouponStatus.EXPIRED);
    assertThat(coupon.expireIfDue(EXPIRES)).isEmpty();

    BuyerCoupon reserved =
        BuyerCoupon.issueWelcome(UUID.randomUUID(), template, BUYER, SIGNED_UP, NOW).coupon();
    reserved.reserve(ORDER, CYCLE, SELLER, PRODUCT, 20_000, Order.ExternalMethod.CREDIT_CARD, NOW);
    assertThat(reserved.expireIfDue(EXPIRES)).isEmpty();
    assertThat(reserved.getStatus()).isEqualTo(BuyerCouponStatus.RESERVED);
  }

  @Test
  @DisplayName("기한이 지난 미사용 쿠폰은 조회 때 EXPIRED로 보인다")
  void showsExpiredStatusAtReadTime() {
    assertThat(coupon.statusAt(NOW)).isEqualTo(BuyerCouponStatus.ISSUED);
    assertThat(coupon.statusAt(EXPIRES)).isEqualTo(BuyerCouponStatus.EXPIRED);
  }

  private long discount(long gross, Instant at) {
    return coupon.discountFor(SELLER, PRODUCT, gross, Order.ExternalMethod.CREDIT_CARD, at);
  }

  private java.util.Optional<CouponHistory> reserve(UUID order) {
    return coupon.reserve(
        order, CYCLE, SELLER, PRODUCT, 20_000, Order.ExternalMethod.DEBIT_CARD, NOW);
  }

  private static void assertRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
    assertThatThrownBy(call)
        .isInstanceOf(CouponRejectedException.class)
        .extracting(e -> ((CouponRejectedException) e).reason())
        .isEqualTo(reason);
  }

  private static void assertHistory(
      CouponHistory history,
      CouponOperation operation,
      BuyerCouponStatus from,
      UUID businessRef,
      long committed,
      long consumed) {
    assertThat(history.getOperation()).isEqualTo(operation);
    assertThat(history.getFromStatus()).isEqualTo(from);
    assertThat(history.getBusinessRef()).isEqualTo(businessRef);
    assertThat(history.getCommittedDelta()).isEqualTo(committed);
    assertThat(history.getConsumedDelta()).isEqualTo(consumed);
  }
}
