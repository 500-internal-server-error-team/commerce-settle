package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.domain.model.PayoutJob;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** OpenAPI Job. Settlement의 운영 작업은 지급 작업이며 resourceId는 지급 ID다. null 필드도 키를 생략하지 않는다. */
@Schema(name = "Job", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record JobResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID resourceId,
    @JsonProperty(required = true) String operation,
    @Schema(allowableValues = {"PENDING", "RUNNING", "RETRY", "REVIEW_REQUIRED", "DONE"})
        @JsonProperty(required = true)
        String status,
    @JsonProperty(required = true) int attempts,
    @JsonProperty(required = true) OffsetDateTime nextRunAt,
    @Schema(nullable = true, description = "지급 작업은 오류 코드를 따로 저장하지 않아 항상 null")
        @JsonProperty(required = true)
        String lastErrorCode,
    @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt) {
  static final String PAYOUT = "PAYOUT";

  public static JobResponse from(PayoutJob job) {
    return new JobResponse(
        job.getId(),
        job.getPayoutId(),
        PAYOUT,
        job.getStatus().name(),
        job.getAttempts(),
        Kst.of(job.getNextRunAt()),
        null,
        job.getVersion(),
        Kst.of(job.getCreatedAt()));
  }

  @Schema(name = "JobPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Page(
      @JsonProperty(required = true) List<JobResponse> items,
      @Schema(minimum = "0") @JsonProperty(required = true) int page,
      @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
      @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
      @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
    public static Page from(PageView<PayoutJob> view) {
      return new Page(
          view.items().stream().map(JobResponse::from).toList(),
          view.page(),
          view.size(),
          view.totalElements(),
          view.totalPages());
    }
  }
}
