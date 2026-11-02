package com.commercesettle.commerce.application.dto;

import com.commercesettle.commerce.domain.model.ShippingAddress;

public record SellerOrderView(OrderView order, ShippingAddress deliveryAddress) {
  @Override
  public String toString() {
    return "SellerOrderView[redacted]";
  }
}
