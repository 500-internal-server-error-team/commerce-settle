package com.commercesettle.payment.presentation.dto;

import com.commercesettle.payment.application.port.QuarantineStore.DeliveryView;
import com.commercesettle.payment.application.port.QuarantineStore.QuarantineView;
import com.commercesettle.payment.application.port.QuarantineStore.ResumeView;
import com.commercesettle.payment.application.service.QuarantineOperationService.QuarantinePage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * OpenAPI PaymentQuarantine. Settlement 격리 운영 API와 같은 모양이다. 원문·키 bytes는 내보내지 않고 크기와 SHA-256만 준다.
 * null 필드도 키를 생략하지 않는다.
 */
@Schema(name = "PaymentQuarantine", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentQuarantineResponse(
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
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public static PaymentQuarantineResponse from(QuarantineView view) {
    return new PaymentQuarantineResponse(
        view.id(),
        view.consumer(),
        view.sourceTopic(),
        view.sourcePartition(),
        view.sourceOffset(),
        view.errorCode(),
        view.bodySize(),
        view.bodySha256(),
        view.version(),
        kst(view.createdAt()),
        view.deliveries().stream().map(Delivery::from).toList());
  }

  private static OffsetDateTime kst(Instant value) {
    return value == null ? null : value.atZone(SEOUL).toOffsetDateTime();
  }

  /** DLT 발행 또는 REPLAY 작업. PUBLISHED는 Kafka 발행 완료이며 업무 반영 성공이 아니다. */
  @Schema(
      name = "PaymentQuarantineDelivery",
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
          kst(view.nextRunAt()),
          kst(view.leaseUntil()),
          view.operatorId(),
          view.reasonCode(),
          view.version(),
          kst(view.createdAt()),
          kst(view.updatedAt()),
          view.resumes().stream().map(Resume::from).toList());
    }
  }

  @Schema(
      name = "PaymentQuarantineResume",
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
          kst(view.createdAt()));
    }
  }

  @Schema(
      name = "PaymentQuarantinePage",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Page(
      @JsonProperty(required = true) List<PaymentQuarantineResponse> items,
      @Schema(minimum = "0") @JsonProperty(required = true) int page,
      @Schema(minimum = "1", maximum = "100") @JsonProperty(required = true) int size,
      @Schema(minimum = "0") @JsonProperty(required = true) long totalElements,
      @Schema(minimum = "0") @JsonProperty(required = true) int totalPages) {
    public static Page from(QuarantinePage view) {
      return new Page(
          view.items().stream().map(PaymentQuarantineResponse::from).toList(),
          view.page(),
          view.size(),
          view.totalElements(),
          view.totalPages());
    }
  }

  /** 원 topic/key/bytes를 고정한다. 새 업무 키·금융 거래를 만들지 않는다. */
  @Schema(
      name = "QuarantineReplayRequest",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record ReplayRequest(
      @Schema(minimum = "0")
          @JsonProperty(required = true)
          @NotNull(message = "필수입니다.")
          @Min(value = 0, message = "0 이상이어야 합니다.")
          Long expectedVersion,
      @Schema(pattern = "^[A-Z][A-Z0-9_]{0,79}$", minLength = 1, maxLength = 80)
          @JsonProperty(required = true)
          @NotNull(message = "필수입니다.")
          @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}", message = "대문자로 시작하는 80자 이하 사유 코드여야 합니다.")
          String reasonCode) {}
}
