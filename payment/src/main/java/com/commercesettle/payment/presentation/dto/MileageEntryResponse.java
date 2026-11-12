package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.MileageEntryView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI MileageEntry. null 필드도 키를 생략하지 않는다. */
@Schema(name = "MileageEntry", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MileageEntryResponse(
    @JsonProperty(required = true) UUID id,
    @Schema(nullable = true) @JsonProperty(required = true) UUID paymentId,
    @Schema(allowableValues = {"SEED", "RESERVE", "RELEASE", "DEBIT", "RESTORE", "ACCRUAL"})
        @JsonProperty(required = true)
        String kind,
    @Schema(minimum = "0", maximum = "10000000") @JsonProperty(required = true) long amount,
    @Schema(minimum = "0") @JsonProperty(required = true) long balanceAfter,
    @JsonProperty(required = true) OffsetDateTime createdAt) {

  public static MileageEntryResponse from(MileageEntryView view) {
    return new MileageEntryResponse(
        view.id(),
        view.paymentId(),
        view.kind().name(),
        view.amount(),
        view.balanceAfter(),
        KstTime.of(view.createdAt()));
  }
}
