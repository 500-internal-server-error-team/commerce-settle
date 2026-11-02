package com.commercesettle.financemock.presentation.dto;

import com.commercesettle.financemock.domain.model.MockPaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(name = "MockApproval", description = "원승인과 PAYOUT 재원·금융 시나리오를 고정한다.")
public record MockApprovalRequest(
    @NotNull UUID businessKey,
    @NotNull MockPaymentMethod method,
    @NotBlank @Size(max = 100) String accountRef,
    @NotNull @Positive @Max(10_000_000) Long amount,
    @NotBlank @Size(max = 32) String scenarioVersion,
    @NotBlank @Size(max = 100) String fundingAccountRef) {}
