package com.commercesettle.commerce.application.dto;

import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import java.time.Instant;
import java.util.UUID;

public record OrderRefundView(
    UUID id,
    UUID orderId,
    String kind,
    String status,
    Instant acceptedAt,
    UUID refundId,
    Instant decisionAt,
    Instant receivedAt,
    long version) {
  public static OrderRefundView from(OrderRefundRequest r) {
    return new OrderRefundView(
        r.getId(),
        r.getOrderId(),
        r.getKind().name(),
        r.getStatus().name(),
        r.getAcceptedAt(),
        r.getRefundId(),
        r.getDecisionAt(),
        r.getReceivedAt(),
        r.getVersion());
  }
}
