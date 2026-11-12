package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.dto.PayoutView;
import com.commercesettle.settlement.domain.model.Payout;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** OpenAPI Payout. null 필드도 키를 생략하지 않는다. */
@Schema(name = "Payout", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PayoutResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) UUID batchId,
    @JsonProperty(required = true) UUID sellerId,
    @JsonProperty(required = true) long amount,
    @Schema(allowableValues = {"PENDING", "PROCESSING", "UNKNOWN", "SUCCEEDED", "DECLINED"})
        @JsonProperty(required = true)
        String status,
    @JsonProperty(required = true) long version,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @Schema(nullable = true, description = "원대역의 최초 SUCCEEDED 시각. 성공 전 null, 성공 후 불변")
        @JsonProperty(required = true)
        OffsetDateTime succeededAt,
    @Schema(description = "지급 생성 때 저장한 수취 시험계정 사본") @JsonProperty(required = true)
        String recipientAccountRef,
    @Schema(
            nullable = true,
            allowableValues = {"UNKNOWN", "CONFIRMED_REJECTION"})
        @JsonProperty(required = true)
        String holdReason,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime nextAttemptAt) {
  public static PayoutResponse from(PayoutView view) {
    Payout payout = view.payout();
    return new PayoutResponse(
        payout.getId(),
        payout.getBatchId(),
        payout.getSellerId(),
        payout.getAmount(),
        payout.getStatus().name(),
        payout.getVersion(),
        Kst.of(payout.getCreatedAt()),
        Kst.of(payout.getSucceededAt()),
        payout.getRecipientAccountRef(),
        payout.getHoldReason() == null ? null : payout.getHoldReason().name(),
        Kst.of(view.nextAttemptAt()));
  }
}
