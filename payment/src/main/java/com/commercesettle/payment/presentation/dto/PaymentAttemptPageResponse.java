package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.PageView;
import com.commercesettle.payment.application.dto.PaymentAttemptView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "PaymentAttemptPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PaymentAttemptPageResponse(
    @JsonProperty(required = true) List<PaymentAttemptResponse> items,
    @Schema(minimum = "0") @JsonProperty(required = true) int page,
    @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
    @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
    @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {

  public static PaymentAttemptPageResponse from(PageView<PaymentAttemptView> view) {
    return new PaymentAttemptPageResponse(
        view.items().stream().map(PaymentAttemptResponse::from).toList(),
        view.page(),
        view.size(),
        view.totalElements(),
        view.totalPages());
  }
}
