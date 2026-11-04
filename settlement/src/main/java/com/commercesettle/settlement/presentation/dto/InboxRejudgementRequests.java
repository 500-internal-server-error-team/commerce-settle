package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.port.InboxOperationStore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class InboxRejudgementRequests {
  private InboxRejudgementRequests() {}

  @Schema(
      name = "InboxRejudgementFilter",
      description = "조회와 같은 조건. 원문·금융 업무 키의 변경 입력은 받지 않는다.",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Filter(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String reasonCode,
      @Size(max = 80) String eventType,
      @JsonProperty(required = true) @NotNull OffsetDateTime receivedBefore,
      UUID inboxId) {
    public InboxOperationStore.Filter toFilter() {
      return new InboxOperationStore.Filter(
          reasonCode, eventType, receivedBefore.toInstant(), inboxId);
    }
  }

  @Schema(
      name = "InboxRejudgementExpectedTarget",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Target(
      @JsonProperty(required = true) @NotNull UUID inboxId,
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion) {
    public InboxOperationStore.ExpectedTarget toTarget() {
      return new InboxOperationStore.ExpectedTarget(inboxId, expectedVersion);
    }
  }

  @Schema(
      name = "InboxRejudgementCreate",
      description = "조건·예상 수·대상 ID와 버전을 대조해 최대 100건을 고정한다.",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Create(
      @JsonProperty(required = true) @NotNull @Valid Filter filter,
      @JsonProperty(required = true) @NotNull @Min(1) @Max(100) Integer expectedCount,
      @JsonProperty(required = true) @NotNull @Size(min = 1, max = 100)
          List<@NotNull @Valid Target> targets,
      @JsonProperty(required = true) @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}")
          String reasonCode) {}

  @Schema(
      name = "InboxRejudgementTransition",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Transition(
      @JsonProperty(required = true) @NotNull @Min(0) Long expectedVersion,
      @JsonProperty(required = true) @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}")
          String reasonCode) {}
}
