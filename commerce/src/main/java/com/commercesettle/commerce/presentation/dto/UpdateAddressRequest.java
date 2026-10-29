package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.ShippingAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(
    name = "AddressUpdate",
    description = "주소 전체 변경. isDefault=true면 기존 기본을 해제하고, 기본 주소에 false를 보내도 기본으로 남는다.")
public record UpdateAddressRequest(
    @NotNull @Size(min = 1, max = 80) String recipient,
    @NotNull @Size(min = 1, max = 30) String phone,
    @NotNull @Size(min = 1, max = 20) String postalCode,
    @NotNull @Size(min = 1, max = 200) String addressLine1,
    @NotNull @Size(max = 200) String addressLine2,
    @NotNull Boolean isDefault,
    @NotNull @Min(0) Long expectedVersion) {
  public ShippingAddress shipping() {
    return new ShippingAddress(recipient, phone, postalCode, addressLine1, addressLine2);
  }
}
