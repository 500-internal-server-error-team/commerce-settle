package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.CouponIssueRunAdminService.RunView;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.List;

public record CouponIssueRunPageResponse(
    List<CouponIssueRunResponse> items, int page, int size, long totalElements, int totalPages) {
  public static CouponIssueRunPageResponse from(PageResult<RunView> result) {
    return new CouponIssueRunPageResponse(
        result.items().stream().map(CouponIssueRunResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
