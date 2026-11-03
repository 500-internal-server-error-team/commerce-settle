package com.commercesettle.financemock.presentation.dto;

import com.commercesettle.financemock.domain.model.AccountPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(name = "MockAccountCreate", description = "추가 전용 시험 계정. 기존 잔액을 덮어쓰지 않는다.")
public record CreateMockAccountRequest(
    @NotNull
        @Pattern(regexp = "PG_A|PG_B|PG_C|EASY_PAY|PAYOUT")
        @Schema(allowableValues = {"PG_A", "PG_B", "PG_C", "EASY_PAY", "PAYOUT"})
        String providerId,
    @NotBlank @Size(max = 100) String accountRef,
    @NotNull AccountPurpose purpose,
    @NotNull @PositiveOrZero Long balance) {}
