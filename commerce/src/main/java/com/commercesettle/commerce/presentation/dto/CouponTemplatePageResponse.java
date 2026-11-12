package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.List;

public record CouponTemplatePageResponse(
    List<CouponTemplateResponse> items, int page, int size, long totalElements, int totalPages) {
  public static CouponTemplatePageResponse from(PageResult<CouponTemplate> result) {
    return new CouponTemplatePageResponse(
        result.items().stream().map(CouponTemplateResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
