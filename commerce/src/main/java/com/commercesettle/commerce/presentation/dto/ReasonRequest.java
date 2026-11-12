package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "ReasonRequest", description = "현재 버전을 확인하고 사유를 남기는 상태 변경 요청")
public record ReasonRequest(
    @NotNull @Min(0) Long expectedVersion, @NotNull @Size(min = 1, max = 200) String reason) {}
