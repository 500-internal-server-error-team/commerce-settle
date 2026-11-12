package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.MileageEntryView;
import com.commercesettle.payment.application.dto.PageView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "MileageEntryPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record MileageEntryPageResponse(
    @JsonProperty(required = true) List<MileageEntryResponse> items,
    @Schema(minimum = "0") @JsonProperty(required = true) int page,
    @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
    @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
    @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {

  public static MileageEntryPageResponse from(PageView<MileageEntryView> view) {
    return new MileageEntryPageResponse(
        view.items().stream().map(MileageEntryResponse::from).toList(),
        view.page(),
        view.size(),
        view.totalElements(),
        view.totalPages());
  }
}
