package com.commercesettle.financemock.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Schema(name = "MockRefund", description = "원승인 provider·시나리오를 계승하는 외부 결제액 전액 환불.")
public record MockRefundRequest(
    @NotNull UUID businessKey, @NotNull @Positive @Max(10_000_000) Long amount) {}
