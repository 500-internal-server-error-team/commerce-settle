package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "ProductCreate", description = "상품 등록. 재고 0인 재고 행을 함께 만든다.")
public record CreateProductRequest(
    @NotNull @Size(min = 1, max = 120) String name,
    @Schema(description = "KRW 원 단위 단가") @NotNull @Min(1) @Max(10_000_000) Long unitPrice,
    @Schema(allowableValues = {"DRAFT", "ON_SALE", "STOPPED"})
        @NotNull
        @Pattern(regexp = ProductResponse.SELLER_STATUSES)
        String status) {}
