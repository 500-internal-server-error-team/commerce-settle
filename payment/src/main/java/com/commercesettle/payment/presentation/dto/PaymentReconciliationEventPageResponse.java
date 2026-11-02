package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.ReconciliationEventPageView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(
    name = "PaymentReconciliationEventPage",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentReconciliationEventPageResponse(
    @JsonProperty(required = true) List<PaymentReconciliationEventResponse> items,
    @Schema(minimum = "0") @JsonProperty(required = true) int page,
    @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
    @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
    @Schema(minimum = "0") @JsonProperty(required = true) int totalPages,
    @JsonProperty(required = true) OffsetDateTime cutoffAt,
    @JsonProperty(required = true) OffsetDateTime snapshotAt,
    @Schema(nullable = true, minLength = 1, maxLength = 2048) @JsonProperty(required = true)
        String nextCursor) {

  public static PaymentReconciliationEventPageResponse from(ReconciliationEventPageView view) {
    return new PaymentReconciliationEventPageResponse(
        view.items().stream().map(PaymentReconciliationEventResponse::from).toList(),
        view.page(),
        view.size(),
        view.totalElements(),
        view.totalPages(),
        KstTime.of(view.cutoffAt()),
        KstTime.of(view.snapshotAt()),
        view.nextCursor());
  }
}
