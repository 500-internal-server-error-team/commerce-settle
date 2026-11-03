package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.Order;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(name = "CouponTemplateCreate", description = "판매자 쿠폰 캠페인 등록. 종류·부담자·판매자는 서버가 정한다.")
public record CreateCouponTemplateRequest(
    @NotNull @Size(min = 1, max = 80) String name,
    @Schema(nullable = true) UUID productId,
    @NotNull @Min(1) @Max(10_000_000) Long discountAmount,
    @NotNull @Min(1) @Max(10_000_000) Long minOrderAmount,
    @NotNull @Min(1) Integer issuanceLimit,
    @NotNull OffsetDateTime validFrom,
    @NotNull OffsetDateTime validUntil,
    @NotNull @Min(1) Long totalBudget,
    @NotNull @Min(0) @Max(10_000_000) Long minSellerNetAmount,
    @NotNull @Size(min = 1, max = 3) List<Order.@NotNull ExternalMethod> allowedMethods) {}
