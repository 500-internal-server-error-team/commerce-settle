package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.BuyerAddressService.StoredAddress;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "Address")
public record AddressResponse(
    String recipient,
    String phone,
    String postalCode,
    String addressLine1,
    String addressLine2,
    boolean isDefault,
    UUID id,
    long version) {
  public static AddressResponse from(StoredAddress address) {
    ShippingAddress shipping = address.shipping();
    return new AddressResponse(
        shipping.recipient(),
        shipping.phone(),
        shipping.postalCode(),
        shipping.addressLine1(),
        shipping.addressLine2(),
        address.isDefault(),
        address.id(),
        address.version());
  }
}
