package com.commercesettle.commerce.application.dto;

import java.util.UUID;

/** 실제 개인정보가 없는 가상 배송지의 주문 당시 사본. HTTP Order 응답에는 노출하지 않는다. */
public record OrderAddressView(
    UUID addressId,
    String recipientName,
    String phone,
    String postalCode,
    String addressLine1,
    String addressLine2) {}
