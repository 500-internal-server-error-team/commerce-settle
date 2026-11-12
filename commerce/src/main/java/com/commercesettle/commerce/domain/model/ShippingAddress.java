package com.commercesettle.commerce.domain.model;

import java.util.Objects;

/** 주소록 한 건의 평문 배송 정보. 저장할 때는 암호문으로만 남기며 로그·예외 메시지에 값을 넣지 않는다. */
public record ShippingAddress(
    String recipient, String phone, String postalCode, String addressLine1, String addressLine2) {
  public static final int MAX_RECIPIENT_LENGTH = 80;
  public static final int MAX_PHONE_LENGTH = 30;
  public static final int MAX_POSTAL_CODE_LENGTH = 20;
  public static final int MAX_ADDRESS_LINE_LENGTH = 200;

  public ShippingAddress {
    requireLength(recipient, "recipient", 1, MAX_RECIPIENT_LENGTH);
    requireLength(phone, "phone", 1, MAX_PHONE_LENGTH);
    requireLength(postalCode, "postalCode", 1, MAX_POSTAL_CODE_LENGTH);
    requireLength(addressLine1, "addressLine1", 1, MAX_ADDRESS_LINE_LENGTH);
    requireLength(addressLine2, "addressLine2", 0, MAX_ADDRESS_LINE_LENGTH);
  }

  private static void requireLength(String value, String name, int min, int max) {
    Objects.requireNonNull(value, name);
    if (value.length() < min || value.length() > max) {
      throw new IllegalArgumentException(name + " length must be " + min + ".." + max);
    }
  }

  @Override
  public String toString() {
    return "ShippingAddress[<redacted>]";
  }
}
