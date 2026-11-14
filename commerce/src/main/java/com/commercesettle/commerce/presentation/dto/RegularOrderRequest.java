package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.RegularOrderCommand;
import com.commercesettle.commerce.application.dto.RegularOrderCommand.ExternalMethod;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(
    name = "OrderCreate",
    description = "주문 생성. 쿠폰·이벤트 입력은 해당 소유 어댑터가 필요하다.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RegularOrderRequest(
    @JsonProperty(required = true) @NotNull UUID productId,
    @JsonProperty(required = true) @NotNull @Min(1) @Max(10) Integer quantity,
    @Schema(nullable = true) @JsonProperty(required = true) UUID eventId,
    @Schema(nullable = true) @JsonProperty(required = true) UUID buyerCouponId,
    @JsonProperty(required = true) @NotNull @Min(0) @Max(10_000_000) Long mileageAmount,
    @JsonProperty(required = true) @NotNull UUID addressId,
    @JsonProperty(required = true) @NotNull @Min(0) Long expectedProductVersion,
    @Schema(nullable = true, minimum = "0") @JsonProperty(required = true) @Min(0)
        Long expectedEventVersion,
    @JsonProperty(required = true) @NotNull @Min(1) @Max(10_000_000) Long expectedTotalAmount,
    @Schema(nullable = true) @JsonProperty(required = true) ExternalMethod externalMethod,
    @Schema(nullable = true) @JsonProperty(required = true) @Size(min = 1, max = 100)
        String methodAccountRef,
    @Schema(nullable = true, description = "시연 전용 회차. 생략하면 일반 정책") UUID demoRunId) {
  public RegularOrderCommand toCommand() {
    return new RegularOrderCommand(
        productId,
        quantity,
        eventId,
        buyerCouponId,
        mileageAmount,
        addressId,
        expectedProductVersion,
        expectedEventVersion,
        expectedTotalAmount,
        externalMethod,
        methodAccountRef,
        demoRunId);
  }

  @Override
  public String toString() {
    return "RegularOrderRequest[redacted]";
  }
}
