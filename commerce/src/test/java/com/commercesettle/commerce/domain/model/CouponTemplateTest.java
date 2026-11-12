package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.CouponCampaignConflictException;
import com.commercesettle.commerce.domain.exception.CouponCampaignConflictException.Reason;
import com.commercesettle.commerce.domain.exception.InvalidCouponTermsException;
import com.commercesettle.commerce.domain.exception.VersionConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 판매자 캠페인의 등록 검증·발급 가능 기간·발급 종료와 판매자 쿠폰 발급을 확인한다. */
class CouponTemplateTest {
  private static final Instant NOW = Instant.parse("2026-11-03T01:00:00Z");
  private static final Instant FROM = NOW.minus(Duration.ofHours(1));
  private static final Instant UNTIL = NOW.plus(Duration.ofDays(7));
  private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final Set<Order.ExternalMethod> CARD =
      EnumSet.of(Order.ExternalMethod.CREDIT_CARD);

  @Test
  @DisplayName("판매자 캠페인은 SELLER 종류·부담자로 만들고 집계는 0에서 시작한다")
  void registersSellerCampaign() {
    CouponTemplate template = seller(3_000, 23_000, 0, 30_000, UNTIL);

    assertThat(template.getKind()).isEqualTo(CouponKind.SELLER);
    assertThat(template.getFunder()).isEqualTo(CouponFunder.SELLER);
    assertThat(template.isSellerCampaignOf(SELLER)).isTrue();
    assertThat(template.isSellerCampaignOf(UUID.randomUUID())).isFalse();
    assertThat(template.getAllowedMethods()).containsExactly(Order.ExternalMethod.CREDIT_CARD);
    assertThat(template.getIssuedCount()).isZero();
    assertThat(template.getCommittedBudget()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    // 할인 후 상품대금 19,999원
    "3000, 22999, 0, 30000, minOrderAmount",
    // 최소 주문금액에서 판매자 정산액 17,000원 < 17,001원
    "3000, 23000, 17001, 30000, minSellerNetAmount",
    // 할인액이 최소 주문금액 이상
    "23000, 23000, 0, 30000, discountAmount",
    // 예산이 할인액보다 작음
    "3000, 23000, 0, 2999, totalBudget"
  })
  @DisplayName("할인 후 20,000원·최소 정산액·할인액·예산 조건을 어기면 해당 필드로 거절한다")
  void rejectsInvalidTerms(long discount, long minOrder, long minNet, long budget, String field) {
    assertThatThrownBy(() -> seller(discount, minOrder, minNet, budget, UNTIL))
        .isInstanceOf(InvalidCouponTermsException.class)
        .extracting(e -> ((InvalidCouponTermsException) e).field())
        .isEqualTo(field);
  }

  @Test
  @DisplayName("종료 시각이 시작보다 앞이거나 이미 지났으면 거절한다")
  void rejectsInvalidPeriod() {
    assertThatThrownBy(() -> seller(3_000, 23_000, 0, 30_000, FROM))
        .extracting(e -> ((InvalidCouponTermsException) e).field())
        .isEqualTo("validUntil");
    assertThatThrownBy(
            () ->
                CouponTemplate.seller(
                    UUID.randomUUID(),
                    SELLER,
                    null,
                    "지난 캠페인",
                    3_000,
                    23_000,
                    10,
                    NOW.minus(Duration.ofDays(2)),
                    NOW.minusSeconds(1),
                    30_000,
                    0,
                    CARD,
                    NOW))
        .extracting(e -> ((InvalidCouponTermsException) e).field())
        .isEqualTo("validUntil");
  }

  @Test
  @DisplayName("발급은 활성 캠페인의 기간 안에서만 하고, 쿠폰 기한은 캠페인 종료 시각이며 할인액을 약속액으로 남긴다")
  void issuesSellerCouponWithinPeriod() {
    CouponTemplate template = seller(3_000, 23_000, 0, 30_000, UNTIL);
    UUID buyer = UUID.randomUUID();

    BuyerCoupon.Issued issued = BuyerCoupon.issueSeller(UUID.randomUUID(), template, buyer, NOW);

    assertThat(issued.coupon().getExpiresAt()).isEqualTo(UNTIL);
    assertThat(issued.coupon().getFunder()).isEqualTo(CouponFunder.SELLER);
    assertThat(issued.coupon().getSellerId()).isEqualTo(SELLER);
    assertThat(issued.history().getCommittedDelta()).isEqualTo(3_000);
    assertConflict(
        () -> BuyerCoupon.issueSeller(UUID.randomUUID(), template, buyer, FROM.minusSeconds(1)),
        Reason.NOT_STARTED);
    assertConflict(
        () -> BuyerCoupon.issueSeller(UUID.randomUUID(), template, buyer, UNTIL), Reason.ENDED);
  }

  @Test
  @DisplayName("지정 상품 캠페인의 쿠폰은 같은 판매자의 그 상품에만 쓸 수 있다")
  void limitsSellerCouponToProduct() {
    BuyerCoupon coupon =
        BuyerCoupon.issueSeller(
                UUID.randomUUID(), seller(3_000, 23_000, 0, 30_000, UNTIL), UUID.randomUUID(), NOW)
            .coupon();

    assertThat(coupon.discountFor(SELLER, PRODUCT, 23_000, Order.ExternalMethod.CREDIT_CARD, NOW))
        .isEqualTo(3_000);
    assertThatThrownBy(
            () ->
                coupon.discountFor(
                    SELLER, UUID.randomUUID(), 23_000, Order.ExternalMethod.CREDIT_CARD, NOW))
        .hasMessageContaining("PRODUCT_MISMATCH");
    assertThatThrownBy(
            () ->
                coupon.discountFor(
                    UUID.randomUUID(), PRODUCT, 23_000, Order.ExternalMethod.CREDIT_CARD, NOW))
        .hasMessageContaining("PRODUCT_MISMATCH");
  }

  @Test
  @DisplayName("발급 종료는 버전이 맞을 때 한 번만 하고, 종료한 캠페인은 새로 발급하지 않는다")
  void closesOnce() {
    CouponTemplate template = seller(3_000, 23_000, 0, 30_000, UNTIL);

    assertThatThrownBy(() -> template.close(1, NOW)).isInstanceOf(VersionConflictException.class);
    template.close(0, NOW);

    assertThat(template.getStatus()).isEqualTo(CouponTemplateStatus.CLOSED);
    assertConflict(() -> template.close(0, NOW), Reason.CLOSED);
    assertConflict(
        () -> BuyerCoupon.issueSeller(UUID.randomUUID(), template, UUID.randomUUID(), NOW),
        Reason.CLOSED);
  }

  private static CouponTemplate seller(
      long discount, long minOrder, long minNet, long budget, Instant until) {
    return CouponTemplate.seller(
        UUID.randomUUID(),
        SELLER,
        PRODUCT,
        "단골 감사",
        discount,
        minOrder,
        10,
        FROM,
        until,
        budget,
        minNet,
        CARD,
        NOW);
  }

  private static void assertConflict(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
    assertThatThrownBy(call)
        .isInstanceOf(CouponCampaignConflictException.class)
        .extracting(e -> ((CouponCampaignConflictException) e).reason())
        .isEqualTo(reason);
  }
}
