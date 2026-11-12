package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.internalauth.InternalCallerFilter;
import com.commercesettle.settlement.application.service.PayoutJobOperationService;
import com.commercesettle.settlement.application.service.SettlementBatchOperationService;
import com.commercesettle.settlement.presentation.dto.JobResponse;
import com.commercesettle.settlement.presentation.dto.OperationRequests;
import com.commercesettle.settlement.presentation.dto.ProblemResponse;
import com.commercesettle.settlement.presentation.dto.SettlementBatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 운영자 전용 지급 작업 조회·재처리와 정산 배치 생성·재개. 운영자 자격만 허용하며 Gateway에 공개하지 않는다. */
@Tag(name = "settlement")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "형식·값 오류",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "401",
      description = "인증 필요/만료",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "403",
      description = "역할·서비스 권한 없음",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "404",
      description = "없거나 타인 소유",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "409",
      description = "상태·버전·기한·잔액·멱등 충돌",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
public class SettlementOperationController {
  private final PayoutJobOperationService jobs;
  private final SettlementBatchOperationService batches;

  public SettlementOperationController(
      PayoutJobOperationService jobs, SettlementBatchOperationService batches) {
    this.jobs = jobs;
    this.batches = batches;
  }

  @Operation(operationId = "get_internal_payout_jobs", summary = "운영 작업 목록")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/internal/payout-jobs")
  public JobResponse.Page list(
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return JobResponse.Page.from(jobs.list(page, size));
  }

  @Operation(operationId = "post_internal_payout_jobs_jobId_replays", summary = "원업무 작업 재처리")
  @ApiResponse(responseCode = "202", description = "비동기 접수; 금융 처리 완료 아님")
  @PostMapping("/internal/payout-jobs/{jobId}/replays")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public JobResponse replay(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID jobId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OperationRequests.Replay r) {
    return JobResponse.from(jobs.replay(operator, jobId, key, r.expectedVersion(), r.reason()));
  }

  @Operation(operationId = "post_internal_settlement_batches", summary = "정산 배치 생성·재개")
  @ApiResponse(responseCode = "202", description = "비동기 접수; 금융 처리 완료 아님")
  @PostMapping("/internal/settlement-batches")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public SettlementBatchResponse create(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OperationRequests.BatchCreate r) {
    return SettlementBatchResponse.from(
        batches.create(operator, key, r.sellerId(), r.batchDate(), r.policyVersion()));
  }
}
