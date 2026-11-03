package com.commercesettle.settlement.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** 운영 API 입력. 상태·버전·마감은 응용 경계에서 다시 검증한다. */
public final class OperationRequests {
  private OperationRequests() {}

  @Schema(
      name = "ReplayRequest",
      description = "기존 업무키/금액/수단 고정. 실행 중 lease와 충돌하면 409. 새 금융거래 생성 불가.",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Replay(
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion,
      @JsonProperty(required = true) @NotBlank @Size(max = 200) String reason) {}

  @Schema(name = "BatchCreate", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record BatchCreate(
      @JsonProperty(required = true) @NotNull UUID sellerId,
      @JsonProperty(required = true) @NotNull LocalDate batchDate,
      @Schema(allowableValues = {"delivery-daily-v1"})
          @JsonProperty(required = true)
          @NotBlank
          @Size(max = 32)
          String policyVersion) {}
}
