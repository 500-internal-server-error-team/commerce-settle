package com.commercesettle.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercesettle.commerce.domain.exception.CouponRejectedException;
import com.commercesettle.commerce.domain.exception.CouponRejectedException.Reason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 월간 우수고객의 등급 경계, 회차의 마감·기한과 상태 전이, GRADE 캠페인·쿠폰 기한과 사용 조건을 확인한다. */
class GradeCouponTest {
  private static final LocalDate MONTH = LocalDate.of(2026, 11, 1);
  private static final Instant CUTOFF = Instant.parse("2026-10-31T15:00:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-11-30T15:00:00Z");

  @ParameterizedTest
  @CsvSource({"1, BRONZE", "299999, BRONZE", "300000, SILVER", "499999, SILVER", "500000, GOLD"})
  @DisplayName("전월 외부 결제액 30만 원 미만은 브론즈, 50만 원 미만은 실버, 그 이상은 골드다")
  void gradesBySpend(long spend, CouponGrade grade) {
    assertThat(CouponGrade.of(spend)).isEqualTo(grade);
  }

  @Test
  @DisplayName("회차는 혜택 월 1일 0시(KST)가 마감이고 전월 1일부터 집계하며 다음 달 1일 0시가 기한이다")
  void runUsesKstMonthBoundaries() {
    CouponIssueRun run = CouponIssueRun.open(UUID.randomUUID(), MONTH.plusDays(9), CUTOFF);

    assertThat(run.getTargetMonth()).isEqualTo(MONTH);
    assertThat(run.getCutoffAt()).isEqualTo(CUTOFF);
    assertThat(run.spendFrom()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
    assertThat(run.expiresAt()).isEqualTo(EXPIRES);
    assertThat(run.getStatus()).isEqualTo(CouponIssueRunStatus.PREPARING);
    assertThatThrownBy(() -> CouponIssueRun.open(UUID.randomUUID(), MONTH, CUTOFF.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("대상이 있으면 ISSUING, 없으면 바로 COMPLETED로 확정하며 기한 전에는 만료하지 않는다")
  void confirmsAndExpires() {
    CouponIssueRun empty = CouponIssueRun.open(UUID.randomUUID(), MONTH, CUTOFF);
    CouponIssueRun run = CouponIssueRun.open(UUID.randomUUID(), MONTH, CUTOFF);

    empty.confirm(0, null, CUTOFF);
    run.confirm(104, 120_000L, CUTOFF);

    assertThat(empty.getStatus()).isEqualTo(CouponIssueRunStatus.COMPLETED);
    assertThat(run.getStatus()).isEqualTo(CouponIssueRunStatus.ISSUING);
    assertThat(run.getCutoffSpend()).isEqualTo(120_000L);
    assertThatThrownBy(() -> run.expire(EXPIRES.minusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    run.expire(EXPIRES);
    assertThat(run.getStatus()).isEqualTo(CouponIssueRunStatus.EXPIRED);
  }

  @Test
  @DisplayName("GRADE 캠페인·쿠폰은 플랫폼 부담이고 혜택 월 동안 쓰며, 기한이 지난 늦은 발급은 하지 않는다")
  void issuesGradeCouponForMonth() {
    CouponTemplate template =
        CouponTemplate.grade(UUID.randomUUID(), CouponGrade.SILVER, MONTH, CUTOFF);
    UUID target = UUID.randomUUID();

    BuyerCoupon.Issued issued =
        BuyerCoupon.issueGrade(UUID.randomUUID(), template, UUID.randomUUID(), target, CUTOFF);

    assertThat(template.getFunder()).isEqualTo(CouponFunder.PLATFORM);
    assertThat(template.getDiscountAmount()).isEqualTo(5_000);
    assertThat(template.getMinOrderAmount()).isEqualTo(70_000);
    assertThat(issued.coupon().getKind()).isEqualTo(CouponKind.GRADE);
    assertThat(issued.coupon().getTargetMonth()).isEqualTo(MONTH);
    assertThat(issued.coupon().getExpiresAt()).isEqualTo(EXPIRES);
    assertThat(issued.history().getBusinessRef()).isEqualTo(target);
    assertThatThrownBy(
            () ->
                BuyerCoupon.issueGrade(
                    UUID.randomUUID(), template, UUID.randomUUID(), target, EXPIRES))
        .hasMessageContaining("EXPIRED");
  }

  @ParameterizedTest
  @CsvSource({"BRONZE, 50000", "SILVER, 70000", "GOLD, 90000"})
  @DisplayName("등급 쿠폰은 최소 주문금액 직전은 거절하고 정각부터 할인하며, 혜택 월 시작 전과 기한 정각에는 쓸 수 없다")
  void appliesGradeMinimumAndMonthBoundaries(CouponGrade grade, long minimum) {
    CouponTemplate template = CouponTemplate.grade(UUID.randomUUID(), grade, MONTH, CUTOFF);
    BuyerCoupon coupon =
        BuyerCoupon.issueGrade(
                UUID.randomUUID(), template, UUID.randomUUID(), UUID.randomUUID(), CUTOFF)
            .coupon();

    assertThat(discount(coupon, minimum, CUTOFF)).isEqualTo(grade.discountAmount());
    assertThat(discount(coupon, minimum, EXPIRES.minusSeconds(1)))
        .isEqualTo(grade.discountAmount());
    assertRejected(() -> discount(coupon, minimum - 1, CUTOFF), Reason.BELOW_MIN_ORDER);
    assertRejected(() -> discount(coupon, minimum, CUTOFF.minusSeconds(1)), Reason.NOT_STARTED);
    assertRejected(() -> discount(coupon, minimum, EXPIRES), Reason.EXPIRED);
  }

  private static long discount(BuyerCoupon coupon, long gross, Instant at) {
    return coupon.discountFor(null, null, gross, Order.ExternalMethod.CREDIT_CARD, at);
  }

  private static void assertRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
    assertThatThrownBy(call)
        .isInstanceOf(CouponRejectedException.class)
        .extracting(e -> ((CouponRejectedException) e).reason())
        .isEqualTo(reason);
  }
}
