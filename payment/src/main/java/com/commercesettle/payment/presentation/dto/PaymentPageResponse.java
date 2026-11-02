package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.PaymentPageView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "PaymentPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PaymentPageResponse(
    @JsonProperty(required = true) List<PaymentResponse> items,
    @Schema(minimum = "0") @JsonProperty(required = true) int page,
    @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
    @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
    @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {

  public static PaymentPageResponse from(PaymentPageView view) {
    return new PaymentPageResponse(
        view.items().stream().map(PaymentResponse::from).toList(),
        view.page(),
        view.size(),
        view.totalElements(),
        view.totalPages());
  }
}
