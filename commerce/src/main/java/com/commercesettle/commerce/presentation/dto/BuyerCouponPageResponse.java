package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.CouponService.CouponView;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.List;

public record BuyerCouponPageResponse(
    List<BuyerCouponResponse> items, int page, int size, long totalElements, int totalPages) {
  public static BuyerCouponPageResponse from(PageResult<CouponView> result) {
    return new BuyerCouponPageResponse(
        result.items().stream().map(BuyerCouponResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
