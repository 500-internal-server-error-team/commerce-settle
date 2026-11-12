package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.CouponIssueRunAdminService.RunView;
import com.commercesettle.commerce.domain.model.CouponIssueRun;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CouponIssueRunResponse(
    UUID id,
    LocalDate targetMonth,
    OffsetDateTime cutoffAt,
    String policyVersion,
    @Schema(nullable = true) UUID replacesRunId,
    @Schema(nullable = true) UUID replacementRunId,
    @Schema(nullable = true) Integer targetCount,
    @Schema(nullable = true) Long cutoffSpend,
    long issuedCount,
    @Schema(allowableValues = {"PREPARING", "ISSUING", "COMPLETED", "EXPIRED", "CANCELLED"})
        String status,
    @Schema(nullable = true) OffsetDateTime cancelledAt,
    @Schema(nullable = true) String cancelledBy,
    @Schema(
            nullable = true,
            allowableValues = {"AGGREGATION_ERROR"})
        String cancellationReasonCode,
    long version,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {
  /** 같은 절대시각을 KST 오프셋으로 내보낸다. */
  public static CouponIssueRunResponse from(RunView view) {
    CouponIssueRun run = view.run();
    return new CouponIssueRunResponse(
        run.getId(),
        run.getTargetMonth(),
        EventResponse.kst(run.getCutoffAt()),
        run.getPolicyVersion(),
        run.getReplacesRunId(),
        view.replacementRunId(),
        run.getTargetCount(),
        run.getCutoffSpend(),
        view.issuedCount(),
        run.getStatus().name(),
        run.getCancelledAt() == null ? null : EventResponse.kst(run.getCancelledAt()),
        run.getCancelledBy(),
        run.getCancellationReasonCode(),
        run.getVersion(),
        EventResponse.kst(run.getCreatedAt()),
        EventResponse.kst(run.getUpdatedAt()));
  }
}
