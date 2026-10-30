package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.CreateOrderCommand.ExternalMethod;
import com.commercesettle.commerce.application.dto.RegularOrderCommand;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(
    name = "OrderCreate",
    description = "일반 주문 생성. eventId·buyerCouponId·expectedEventVersion은 null만 지원한다.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RegularOrderRequest(
    @JsonProperty(required = true) @NotNull UUID productId,
    @JsonProperty(required = true) @NotNull @Min(1) @Max(10) Integer quantity,
    @Schema(nullable = true) @JsonProperty(required = true) @Null UUID eventId,
    @Schema(nullable = true) @JsonProperty(required = true) @Null UUID buyerCouponId,
    @JsonProperty(required = true) @NotNull @Min(0) @Max(10_000_000) Long mileageAmount,
    @JsonProperty(required = true) @NotNull UUID addressId,
    @JsonProperty(required = true) @NotNull @Min(0) Long expectedProductVersion,
    @Schema(nullable = true, minimum = "0") @JsonProperty(required = true) @Null
        Long expectedEventVersion,
    @JsonProperty(required = true) @NotNull @Min(1) @Max(10_000_000) Long expectedTotalAmount,
    @Schema(nullable = true) @JsonProperty(required = true) ExternalMethod externalMethod,
    @Schema(nullable = true) @JsonProperty(required = true) @Size(min = 1, max = 100)
        String methodAccountRef) {
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
        methodAccountRef);
  }

  @Override
  public String toString() {
    return "RegularOrderRequest[redacted]";
  }
}
