package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.CouponService.CouponView;
import com.commercesettle.commerce.domain.model.BuyerCoupon;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public record BuyerCouponResponse(
    UUID id,
    UUID templateId,
    @Schema(nullable = true) UUID sellerId,
    @Schema(nullable = true) UUID productId,
    String name,
    long discountAmount,
    long minOrderAmount,
    OffsetDateTime validFrom,
    OffsetDateTime expiresAt,
    @Schema(allowableValues = {"ISSUED", "RESERVED", "USED", "EXPIRED"}) String status,
    long version,
    @Schema(allowableValues = {"WELCOME", "GRADE", "SELLER"}) String kind,
    @Schema(allowableValues = {"PLATFORM", "SELLER"}) String funder,
    @Schema(nullable = true, pattern = "^\\d{4}-(0[1-9]|1[0-2])$") String targetMonth,
    List<String> allowedMethods) {
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

  /** 같은 절대시각을 KST 오프셋으로 내보낸다. */
  public static BuyerCouponResponse from(CouponView view) {
    BuyerCoupon coupon = view.coupon();
    return new BuyerCouponResponse(
        coupon.getId(),
        coupon.getTemplateId(),
        coupon.getSellerId(),
        coupon.getProductId(),
        view.name(),
        coupon.getDiscountAmount(),
        coupon.getMinOrderAmount(),
        EventResponse.kst(coupon.getValidFrom()),
        EventResponse.kst(coupon.getExpiresAt()),
        view.status().name(),
        coupon.getVersion(),
        coupon.getKind().name(),
        coupon.getFunder().name(),
        coupon.getTargetMonth() == null ? null : coupon.getTargetMonth().format(MONTH),
        coupon.getAllowedMethods().stream().map(Enum::name).toList());
  }
}
