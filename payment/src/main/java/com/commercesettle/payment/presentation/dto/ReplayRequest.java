package com.commercesettle.payment.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 원업무 재처리 요청. 사유는 감사 기록의 사유 코드로 남기므로 대문자·숫자·밑줄 코드만 받는다. */
@Schema(
    name = "ReplayRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    description = "기존업무키/금액/수단고정. 실행중 lease와충돌하면409. 새금융거래생성불가.")
public record ReplayRequest(
    @Schema(minimum = "0")
        @JsonProperty(required = true)
        @NotNull(message = "필수입니다.")
        @Min(value = 0, message = "0 이상이어야 합니다.")
        Long expectedVersion,
    @Schema(minLength = 1, maxLength = 200)
        @JsonProperty(required = true)
        @NotNull(message = "필수입니다.")
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}", message = "대문자로 시작하는 80자 이하 사유 코드여야 합니다.")
        String reason) {}
