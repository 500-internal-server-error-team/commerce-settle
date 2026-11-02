package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(name = "CouponIssueRequest", description = "판매자가 쿠폰을 지급할 등록 구매자.")
public record IssueCouponRequest(@NotNull UUID buyerId) {}
