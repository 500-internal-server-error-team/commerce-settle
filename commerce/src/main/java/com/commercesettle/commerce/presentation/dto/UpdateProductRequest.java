package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "ProductUpdate", description = "상품 전체 변경. 기존 주문의 가격 사본은 바뀌지 않는다.")
public record UpdateProductRequest(
    @NotNull @Size(min = 1, max = 120) String name,
    @Schema(description = "KRW 원 단위 단가") @NotNull @Min(1) @Max(10_000_000) Long unitPrice,
    @Schema(allowableValues = {"DRAFT", "ON_SALE", "STOPPED"})
        @NotNull
        @Pattern(regexp = ProductResponse.SELLER_STATUSES)
        String status,
    @NotNull @Min(0) Long expectedVersion) {}
