package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.dto.OrderQuoteView;
import com.commercesettle.commerce.application.dto.OrderRefundView;
import com.commercesettle.commerce.application.dto.PaymentContextView;
import com.commercesettle.commerce.application.dto.SellerOrderView;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public final class OrderWorkflowResponses {
  @Schema(
      name = "RefundClosureConfirmation",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record ClosureConfirmation(
      @JsonProperty(required = true) UUID id,
      @JsonProperty(required = true) UUID requestId,
      @JsonProperty(required = true) UUID refundId,
      @Schema(minimum = "0") @JsonProperty(required = true) long orderVersion,
      @JsonProperty(required = true) OffsetDateTime confirmedAt) {}

  private OrderWorkflowResponses() {}

  private static OffsetDateTime time(Instant value) {
    return value == null ? null : value.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
  }

  @Schema(name = "OrderQuote", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Quote(
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long grossAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long couponAmount,
      @Schema(minimum = "1", maximum = "10000000") @JsonProperty(required = true) long totalAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long mileageAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long externalAmount,
      @Schema(minimum = "0") @JsonProperty(required = true) long productVersion,
      @Schema(nullable = true) @JsonProperty(required = true) Long eventVersion,
      @JsonProperty(required = true) OffsetDateTime asOf,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long sellerCouponAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long platformCouponAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long settlementBaseAmount) {
    public static Quote from(OrderQuoteView v) {
      return new Quote(
          v.grossAmount(),
          v.couponAmount(),
          v.totalAmount(),
          v.mileageAmount(),
          v.externalAmount(),
          v.productVersion(),
          v.eventVersion(),
          time(v.asOf()),
          v.sellerCouponAmount(),
          v.platformCouponAmount(),
          v.settlementBaseAmount());
    }
  }

  @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record DeliveryAddress(
      @Schema(minLength = 1, maxLength = 80) @JsonProperty(required = true) String recipient,
      @Schema(minLength = 1, maxLength = 30) @JsonProperty(required = true) String phone,
      @Schema(minLength = 1, maxLength = 20) @JsonProperty(required = true) String postalCode,
      @Schema(minLength = 1, maxLength = 200) @JsonProperty(required = true) String addressLine1,
      @Schema(minLength = 0, maxLength = 200) @JsonProperty(required = true) String addressLine2,
      @JsonProperty(required = true) boolean isDefault) {
    @Override
    public String toString() {
      return "DeliveryAddress[redacted]";
    }
  }

  @Schema(name = "SellerOrder", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record SellerOrder(
      @JsonProperty(required = true) OrderResponse order,
      @Schema(nullable = true) @JsonProperty(required = true) DeliveryAddress deliveryAddress) {
    public static SellerOrder from(SellerOrderView v) {
      var a = v.deliveryAddress();
      return new SellerOrder(
          OrderResponse.from(v.order()),
          a == null
              ? null
              : new DeliveryAddress(
                  a.recipient(),
                  a.phone(),
                  a.postalCode(),
                  a.addressLine1(),
                  a.addressLine2(),
                  false));
    }
  }

  @Schema(name = "PaymentContext", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record PaymentContext(
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long grossAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long couponAmount,
      @Schema(minimum = "1", maximum = "10000000") @JsonProperty(required = true) long totalAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long mileageAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long externalAmount,
      @JsonProperty(required = true) UUID orderId,
      @JsonProperty(required = true) UUID buyerId,
      @JsonProperty(required = true) UUID sellerId,
      @Schema(minimum = "0") @JsonProperty(required = true) long orderVersion,
      @JsonProperty(required = true) UUID paymentCycleId,
      @JsonProperty(required = true) UUID productId,
      @Schema(
              allowableValues = {
                "PAYMENT_PENDING",
                "PAID",
                "DECLINED",
                "CANCEL_PENDING",
                "CANCELLED",
                "REFUND_PENDING",
                "REFUNDED",
                "PURCHASE_CONFIRMED"
              })
          @JsonProperty(required = true)
          String status,
      @Schema(minLength = 1, maxLength = 32) @JsonProperty(required = true) String policyVersion,
      @JsonProperty(required = true) OffsetDateTime paymentWaitUntil,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long sellerCouponAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long platformCouponAmount,
      @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true)
          long settlementBaseAmount) {
    public static PaymentContext from(PaymentContextView v) {
      return new PaymentContext(
          v.grossAmount(),
          v.couponAmount(),
          v.totalAmount(),
          v.mileageAmount(),
          v.externalAmount(),
          v.orderId(),
          v.buyerId(),
          v.sellerId(),
          v.orderVersion(),
          v.paymentCycleId(),
          v.productId(),
          v.status(),
          v.policyVersion(),
          time(v.paymentWaitUntil()),
          v.sellerCouponAmount(),
          v.platformCouponAmount(),
          v.settlementBaseAmount());
    }
  }

  @Schema(
      name = "OrderRefundRequest",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Refund(
      @JsonProperty(required = true) UUID id,
      @JsonProperty(required = true) UUID orderId,
      @Schema(allowableValues = {"CANCEL", "RETURN"}) @JsonProperty(required = true) String kind,
      @Schema(
              allowableValues = {
                "PENDING_SELLER",
                "APPROVED",
                "REFUNDING",
                "COMPLETED",
                "REJECTED",
                "WITHDRAWN",
                "FAILED_REVIEW",
                "CLOSED"
              })
          @JsonProperty(required = true)
          String status,
      @JsonProperty(required = true) OffsetDateTime acceptedAt,
      @Schema(nullable = true) @JsonProperty(required = true) UUID refundId,
      @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime decisionAt,
      @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime receivedAt,
      @Schema(minimum = "0") @JsonProperty(required = true) long version) {
    public static Refund from(OrderRefundView v) {
      return new Refund(
          v.id(),
          v.orderId(),
          v.kind(),
          v.status(),
          time(v.acceptedAt()),
          v.refundId(),
          time(v.decisionAt()),
          time(v.receivedAt()),
          v.version());
    }
  }

  @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Page<T>(
      @JsonProperty(required = true) List<T> items,
      @JsonProperty(required = true) int page,
      @JsonProperty(required = true) int size,
      @JsonProperty(required = true) long totalElements,
      @JsonProperty(required = true) int totalPages) {
    public static <V, T> Page<T> from(PageResult<V> p, java.util.function.Function<V, T> mapper) {
      return new Page<>(
          p.items().stream().map(mapper).toList(),
          p.page(),
          p.size(),
          p.totalElements(),
          p.totalPages());
    }
  }
}
