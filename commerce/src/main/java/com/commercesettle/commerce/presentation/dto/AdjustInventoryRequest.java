package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "InventoryAdjustment", description = "일반 가용 재고 증감. 0은 허용하지 않는다.")
public record AdjustInventoryRequest(
    @NotNull @Min(-10_000_000) @Max(10_000_000) @NonZero Long delta,
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Size(min = 1, max = 200) String reason) {}
