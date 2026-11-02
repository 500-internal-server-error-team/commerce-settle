package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(name = "CouponIssueRunReplacementRequest", description = "발급 전 집계 오류 회차의 교정 요청.")
public record CouponIssueRunReplacementRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Pattern(regexp = "AGGREGATION_ERROR") String reasonCode) {}
