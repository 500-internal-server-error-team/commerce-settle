package com.commercesettle.payment.application.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 금융 처리 트랜잭션에서 고정한 알림 사본. 발송할 때 현재 지갑을 다시 조회하지 않는다. */
public record PaymentNoticeRequestedPayload(
    String kind,
    UUID businessRef,
    UUID paymentId,
    UUID paymentCycleId,
    UUID orderId,
    UUID buyerId,
    UUID sellerId,
    UUID refundId,
    long totalAmount,
    long externalAmount,
    long mileageAmount,
    Long mileageBalanceAfter,
    Long walletVersion,
    OffsetDateTime processedAt) {}
