package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.dto.PageView;
import com.commercesettle.payment.application.dto.PaymentJobView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "JobPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record JobPageResponse(
    @JsonProperty(required = true) List<JobResponse> items,
    @Schema(minimum = "0") @JsonProperty(required = true) int page,
    @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
    @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
    @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {

  public static JobPageResponse from(PageView<PaymentJobView> view) {
    return new JobPageResponse(
        view.items().stream().map(JobResponse::from).toList(),
        view.page(),
        view.size(),
        view.totalElements(),
        view.totalPages());
  }
}
