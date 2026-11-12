package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "SellerProfileUpdate", description = "판매자 표시명·시험 수취 계정 변경. 판매 상태는 바꾸지 않는다.")
public record SellerProfileUpdateRequest(
    @NotNull @Size(min = 1, max = 80) String displayName,
    @Schema(description = "시험용 지급 계정 참조") @NotNull @Size(min = 1, max = 100)
        String payoutAccountRef,
    @NotNull @Min(0) Long expectedVersion) {}
