package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.repository.CouponIssueTargetRepository.TargetView;
import com.commercesettle.commerce.domain.repository.PageResult;
import java.util.List;
import java.util.UUID;

/** 월간 회차에 저장된 순위와 쿠폰 발급 결과. 계정 이메일·주소는 반환하지 않는다. */
public record CouponIssueTargetResponse(
    UUID id,
    UUID buyerId,
    long externalSpend,
    int rankNo,
    String grade,
    long discountAmount,
    UUID buyerCouponId,
    String status) {
  static CouponIssueTargetResponse from(TargetView target) {
    return new CouponIssueTargetResponse(
        target.id(),
        target.buyerId(),
        target.externalSpend(),
        target.rankNo(),
        target.grade(),
        target.discountAmount(),
        target.buyerCouponId(),
        target.status());
  }

  public record Page(
      List<CouponIssueTargetResponse> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {
    public static Page from(PageResult<TargetView> result) {
      return new Page(
          result.items().stream().map(CouponIssueTargetResponse::from).toList(),
          result.page(),
          result.size(),
          result.totalElements(),
          result.totalPages());
    }
  }
}
