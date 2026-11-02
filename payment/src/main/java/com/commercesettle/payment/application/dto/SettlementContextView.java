package com.commercesettle.payment.application.dto;

import java.util.UUID;

/** Settlement가 정산 항목을 만들 때 대조하는 확정 결제와 최초 수수료·판매자 순액. */
public record SettlementContextView(
    PaymentView payment,
    long feeAmount,
    long sellerNetAmount,
    UUID sellerId,
    UUID productId,
    String policyVersion) {}
