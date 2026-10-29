package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.ShippingAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "AddressCreate", description = "첫 주소는 isDefault와 관계없이 기본 주소가 된다.")
public record CreateAddressRequest(
    @NotNull @Size(min = 1, max = 80) String recipient,
    @NotNull @Size(min = 1, max = 30) String phone,
    @NotNull @Size(min = 1, max = 20) String postalCode,
    @NotNull @Size(min = 1, max = 200) String addressLine1,
    @NotNull @Size(max = 200) String addressLine2,
    @NotNull Boolean isDefault) {
  public ShippingAddress shipping() {
    return new ShippingAddress(recipient, phone, postalCode, addressLine1, addressLine2);
  }
}
