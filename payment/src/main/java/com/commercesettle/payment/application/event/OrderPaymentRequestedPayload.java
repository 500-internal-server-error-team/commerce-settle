package com.commercesettle.payment.application.event;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record OrderPaymentRequestedPayload(
    UUID orderId,
    UUID buyerId,
    UUID sellerId,
    UUID paymentCycleId,
    UUID productId,
    long orderVersion,
    OrderPaymentRequestedPayload.Status status,
    String policyVersion,
    OffsetDateTime paymentWaitUntil,
    OrderPaymentRequestedPayload.ExternalMethod externalMethod,
    String methodAccountRef,
    long grossAmount,
    long couponAmount,
    long totalAmount,
    long mileageAmount,
    long externalAmount,
    long sellerCouponAmount,
    long platformCouponAmount,
    long settlementBaseAmount) {

  public OrderPaymentRequestedPayload {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(buyerId, "buyerId");
    Objects.requireNonNull(sellerId, "sellerId");
    Objects.requireNonNull(paymentCycleId, "paymentCycleId");
    Objects.requireNonNull(productId, "productId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(policyVersion, "policyVersion");
    Objects.requireNonNull(paymentWaitUntil, "paymentWaitUntil");

    if (orderVersion < 0) {
      throw new IllegalArgumentException("orderVersion must not be negative");
    }
    if (policyVersion.isEmpty() || policyVersion.length() > 32) {
      throw new IllegalArgumentException("policyVersion length must be between 1 and 32");
    }

    requireAmount("grossAmount", grossAmount, 0);
    requireAmount("couponAmount", couponAmount, 0);
    requireAmount("totalAmount", totalAmount, 1);
    requireAmount("mileageAmount", mileageAmount, 0);
    requireAmount("externalAmount", externalAmount, 0);
    requireAmount("sellerCouponAmount", sellerCouponAmount, 0);
    requireAmount("platformCouponAmount", platformCouponAmount, 0);
    requireAmount("settlementBaseAmount", settlementBaseAmount, 0);

    if (methodAccountRef != null
        && (methodAccountRef.isEmpty() || methodAccountRef.length() > 100)) {
      throw new IllegalArgumentException("methodAccountRef length must be between 1 and 100");
    }
    if (externalAmount > 0) {
      Objects.requireNonNull(externalMethod, "externalMethod");
      Objects.requireNonNull(methodAccountRef, "methodAccountRef");
    }
  }

  private static void requireAmount(String field, long amount, long minimum) {
    if (amount < minimum || amount > 10_000_000L) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and 10000000");
    }
  }

  public enum Status {
    PAYMENT_PENDING,
    PAID,
    DECLINED,
    CANCEL_PENDING,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED,
    PURCHASE_CONFIRMED
  }

  public enum ExternalMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    EASY_PAY
  }
}
