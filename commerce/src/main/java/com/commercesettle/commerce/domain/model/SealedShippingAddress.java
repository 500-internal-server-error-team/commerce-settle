package com.commercesettle.commerce.domain.model;

import java.util.Objects;

/** {@link ShippingAddress}의 저장용 암호문. 주소 칸은 우편번호와 두 주소 줄을 함께 담는다. */
public record SealedShippingAddress(byte[] recipient, byte[] phone, byte[] address) {
  public SealedShippingAddress {
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(phone, "phone");
    Objects.requireNonNull(address, "address");
  }
}
