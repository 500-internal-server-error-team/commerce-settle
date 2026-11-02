package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.CouponService;
import com.commercesettle.commerce.presentation.dto.BuyerCouponPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Buyer coupons", description = "구매자 본인 쿠폰")
@RestController
@RequestMapping("/api/buyers/me/coupons")
public class BuyerCouponController {
  private final CouponService coupons;

  public BuyerCouponController(CouponService coupons) {
    this.coupons = coupons;
  }

  @Operation(
      operationId = "get_api_buyers_me_coupons",
      summary = "본인 쿠폰 목록",
      description = "발급 시각 내림차순이다. 기한이 지난 미사용 쿠폰은 EXPIRED로 보인다.")
  @GetMapping
  public BuyerCouponPageResponse list(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return BuyerCouponPageResponse.from(coupons.buyerCoupons(buyer.id(), page, size));
  }
}
