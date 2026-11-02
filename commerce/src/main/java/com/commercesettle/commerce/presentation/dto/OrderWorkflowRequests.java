package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.model.OrderRefundRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 주문·취소/반품 API 입력. 상태·소유권·금액은 응용/도메인 경계에서 다시 검증한다. */
public final class OrderWorkflowRequests {
  private OrderWorkflowRequests() {}

  @Schema(name = "VersionRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Version(@JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion) {}

  @Schema(name = "ReasonRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Reason(
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion,
      @JsonProperty(required = true) @NotBlank @Size(max = 200) String reason) {}

  @Schema(name = "ShipmentRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Shipment(
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion,
      @JsonProperty(required = true) @NotBlank @Size(max = 100) String shipmentReference) {}

  @Schema(name = "OrderQuoteRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Quote(
      @JsonProperty(required = true) @NotNull UUID productId,
      @JsonProperty(required = true) @NotNull @Min(1) @Max(10) Integer quantity,
      @Schema(nullable = true) @JsonProperty(required = true) UUID eventId,
      @Schema(nullable = true) @JsonProperty(required = true) UUID buyerCouponId,
      @JsonProperty(required = true) @NotNull @Min(0) @Max(10000000) Long mileageAmount,
      @Schema(nullable = true) @JsonProperty(required = true)
          Order.ExternalMethod externalMethod) {}

  @Schema(name = "OrderRefundCreate", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record RefundCreate(
      @JsonProperty(required = true) @NotNull OrderRefundRequest.Kind kind,
      @JsonProperty(required = true) @NotBlank @Size(max = 200) String reason,
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion) {}

  public enum DecisionKind {
    APPROVE,
    REJECT
  }

  @Schema(name = "RefundDecision", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Decision(
      @JsonProperty(required = true) @NotNull DecisionKind decision,
      @JsonProperty(required = true) @NotBlank @Size(max = 200) String reason,
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion) {}

  @Schema(
      name = "FailedRefundClosure",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Closure(
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion,
      @JsonProperty(required = true) @NotBlank @Size(max = 200) String reason,
      @JsonProperty(required = true) @NotBlank @Size(max = 200) String customerConfirmationRef) {}
}
