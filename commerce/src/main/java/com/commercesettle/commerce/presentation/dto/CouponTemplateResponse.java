package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.CouponTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CouponTemplateResponse(
    String name,
    @Schema(nullable = true) UUID productId,
    long discountAmount,
    long minOrderAmount,
    int issuanceLimit,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    UUID id,
    UUID sellerId,
    int issuedCount,
    @Schema(allowableValues = {"ACTIVE", "CLOSED"}) String status,
    long version,
    @Schema(allowableValues = {"SELLER"}) String kind,
    @Schema(allowableValues = {"SELLER"}) String funder,
    List<String> allowedMethods,
    long totalBudget,
    long committedBudget,
    long consumedBudget,
    long minSellerNetAmount) {
  /** 같은 절대시각을 KST 오프셋으로 내보낸다. 판매자 캠페인만 이 응답을 쓴다. */
  public static CouponTemplateResponse from(CouponTemplate template) {
    return new CouponTemplateResponse(
        template.getName(),
        template.getProductId(),
        template.getDiscountAmount(),
        template.getMinOrderAmount(),
        template.getIssuanceLimit(),
        EventResponse.kst(template.getValidFrom()),
        EventResponse.kst(template.getValidUntil()),
        template.getId(),
        template.getSellerId(),
        template.getIssuedCount(),
        template.getStatus().name(),
        template.getVersion(),
        template.getKind().name(),
        template.getFunder().name(),
        template.getAllowedMethods().stream().map(Enum::name).toList(),
        template.getTotalBudget(),
        template.getCommittedBudget(),
        template.getConsumedBudget(),
        template.getMinSellerNetAmount());
  }
}
