package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.CreateOrderCommand;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(
    name = "OrderStubCreate",
    description = "로컬 개발용 입력. 주소는 자동 선택하며 쿠폰·이벤트 연동은 TODO이다.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateOrderRequest(
    @JsonProperty(required = true) @NotNull UUID productId,
    @JsonProperty(required = true) @NotNull @Min(1) @Max(10) Integer quantity,
    @JsonProperty(required = true) @NotNull @Min(0) @Max(10_000_000) Long mileageAmount,
    @JsonProperty(required = true) @NotNull @Min(0) Long expectedProductVersion,
    @JsonProperty(required = true) @NotNull @Min(1) @Max(10_000_000) Long expectedTotalAmount,
    @Schema(nullable = true) @JsonProperty(required = true) ExternalMethod externalMethod,
    @Schema(nullable = true) @JsonProperty(required = true) @Size(min = 1, max = 100)
        String methodAccountRef) {
  public CreateOrderCommand toCommand() {
    return new CreateOrderCommand(
        productId,
        quantity,
        mileageAmount,
        expectedProductVersion,
        expectedTotalAmount,
        externalMethod == null ? null : externalMethod.name(),
        methodAccountRef);
  }

  public enum ExternalMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    EASY_PAY
  }
}
