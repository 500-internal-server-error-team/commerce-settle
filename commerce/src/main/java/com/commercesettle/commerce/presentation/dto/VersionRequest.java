package com.commercesettle.commerce.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "VersionRequest", description = "현재 버전을 확인한 뒤 상태를 바꾸는 요청")
public record VersionRequest(@NotNull @Min(0) Long expectedVersion) {}
