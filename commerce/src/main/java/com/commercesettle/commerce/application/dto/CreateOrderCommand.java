package com.commercesettle.commerce.application.dto;

import java.util.UUID;

/** 개발용 주문 생성 입력. 신원·가상 주소는 서버에서 정한다. */
public record CreateOrderCommand(
    UUID productId,
    int quantity,
    long mileageAmount,
    long expectedProductVersion,
    long expectedTotalAmount,
    String externalMethod,
    String methodAccountRef) {}
