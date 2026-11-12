package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.port.QuarantineStore.DeliveryView;
import com.commercesettle.settlement.application.port.QuarantineStore.QuarantineView;
import com.commercesettle.settlement.application.port.QuarantineStore.ResumeView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** OpenAPI SettlementQuarantine. 원문·키 bytes는 내보내지 않고 크기와 SHA-256만 준다. null 필드도 키를 생략하지 않는다. */
@Schema(
    name = "SettlementQuarantine",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record QuarantineResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) String consumer,
    @JsonProperty(required = true) String sourceTopic,
    @JsonProperty(required = true) int sourcePartition,
    @JsonProperty(required = true) long sourceOffset,
    @JsonProperty(required = true) String errorCode,
    @Schema(nullable = true, description = "원문 bytes 크기. 원문이 null이면 null")
        @JsonProperty(required = true)
        Integer bodySize,
    @Schema(nullable = true, description = "원문 SHA-256(hex). 원문이 null이면 null")
        @JsonProperty(required = true)
        String bodySha256,
    @Schema(description = "재처리 요청의 expectedVersion") @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @JsonProperty(required = true) List<Delivery> deliveries) {

  public static QuarantineResponse from(QuarantineView view) {
    return new QuarantineResponse(
        view.id(),
        view.consumer(),
        view.sourceTopic(),
        view.sourcePartition(),
        view.sourceOffset(),
        view.errorCode(),
        view.bodySize(),
        view.bodySha256(),
        view.version(),
        Kst.of(view.createdAt()),
        view.deliveries().stream().map(Delivery::from).toList());
  }

  /** DLT 발행 또는 REPLAY 작업. PUBLISHED는 Kafka 발행 완료이며 업무 반영 성공이 아니다. */
  @Schema(
      name = "SettlementQuarantineDelivery",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Delivery(
      @JsonProperty(required = true) UUID id,
      @JsonProperty(required = true) UUID quarantineId,
      @Schema(allowableValues = {"DLT", "REPLAY"}) @JsonProperty(required = true) String kind,
      @JsonProperty(required = true) String topic,
      @Schema(allowableValues = {"PENDING", "RUNNING", "RETRY", "PUBLISHED", "REVIEW_REQUIRED"})
          @JsonProperty(required = true)
          String status,
      @JsonProperty(required = true) int attempts,
      @JsonProperty(required = true) OffsetDateTime nextRunAt,
      @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime leaseUntil,
      @Schema(nullable = true, description = "REPLAY 요청자. DLT 작업은 null")
          @JsonProperty(required = true)
          String operatorId,
      @Schema(nullable = true, description = "REPLAY 사유. DLT 작업은 null")
          @JsonProperty(required = true)
          String reasonCode,
      @Schema(description = "재개 요청의 expectedVersion") @JsonProperty(required = true) long version,
      @JsonProperty(required = true) OffsetDateTime createdAt,
      @JsonProperty(required = true) OffsetDateTime updatedAt,
      @JsonProperty(required = true) List<Resume> resumes) {
    public static Delivery from(DeliveryView view) {
      return new Delivery(
          view.id(),
          view.quarantineId(),
          view.kind(),
          view.topic(),
          view.status(),
          view.attempts(),
          Kst.of(view.nextRunAt()),
          Kst.of(view.leaseUntil()),
          view.operatorId(),
          view.reasonCode(),
          view.version(),
          Kst.of(view.createdAt()),
          Kst.of(view.updatedAt()),
          view.resumes().stream().map(Resume::from).toList());
    }
  }

  @Schema(
      name = "SettlementQuarantineResume",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Resume(
      @JsonProperty(required = true) UUID id,
      @JsonProperty(required = true) long expectedVersion,
      @JsonProperty(required = true) int previousAttempts,
      @JsonProperty(required = true) String operatorId,
      @JsonProperty(required = true) String reasonCode,
      @JsonProperty(required = true) OffsetDateTime createdAt) {
    static Resume from(ResumeView view) {
      return new Resume(
          view.id(),
          view.expectedVersion(),
          view.previousAttempts(),
          view.operatorId(),
          view.reasonCode(),
          Kst.of(view.createdAt()));
    }
  }

  @Schema(
      name = "SettlementQuarantinePage",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Page(
      @JsonProperty(required = true) List<QuarantineResponse> items,
      @Schema(minimum = "0") @JsonProperty(required = true) int page,
      @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
      @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
      @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
    public static Page from(PageView<QuarantineView> view) {
      return new Page(
          view.items().stream().map(QuarantineResponse::from).toList(),
          view.page(),
          view.size(),
          view.totalElements(),
          view.totalPages());
    }
  }
}
