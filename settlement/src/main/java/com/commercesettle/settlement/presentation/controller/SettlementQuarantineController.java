package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.internalauth.InternalCallerFilter;
import com.commercesettle.settlement.application.service.QuarantineOperationService;
import com.commercesettle.settlement.presentation.dto.OperationRequests;
import com.commercesettle.settlement.presentation.dto.ProblemResponse;
import com.commercesettle.settlement.presentation.dto.QuarantineResponse;
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

/** 운영자 전용 격리 원문 조회와 원키 재처리·소진 발행 재개. 운영자 자격만 허용하며 Gateway에 공개하지 않는다. */
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
public class SettlementQuarantineController {
  private final QuarantineOperationService quarantines;

  public SettlementQuarantineController(QuarantineOperationService quarantines) {
    this.quarantines = quarantines;
  }

  @Operation(operationId = "get_internal_settlement_quarantines", summary = "격리 원문 목록")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/internal/settlement-quarantines")
  public QuarantineResponse.Page list(
      @RequestParam(required = false) String deliveryStatus,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return QuarantineResponse.Page.from(quarantines.list(deliveryStatus, page, size));
  }

  @Operation(operationId = "get_internal_settlement_quarantines_quarantineId", summary = "격리 원문 조회")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/internal/settlement-quarantines/{quarantineId}")
  public QuarantineResponse get(@PathVariable UUID quarantineId) {
    return QuarantineResponse.from(quarantines.get(quarantineId));
  }

  @Operation(
      operationId = "post_internal_settlement_quarantines_quarantineId_replays",
      summary = "격리 원문 원키 재처리")
  @ApiResponse(responseCode = "202", description = "발행 작업 접수; 업무 반영 완료 아님")
  @PostMapping("/internal/settlement-quarantines/{quarantineId}/replays")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public QuarantineResponse replay(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID quarantineId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OperationRequests.QuarantineReplay r) {
    return QuarantineResponse.from(
        quarantines.replay(operator, quarantineId, key, r.expectedVersion(), r.reasonCode()));
  }

  @Operation(
      operationId = "post_internal_settlement_quarantine_deliveries_deliveryId_resumes",
      summary = "소진 발행 작업 재개")
  @ApiResponse(responseCode = "202", description = "발행 작업 접수; 업무 반영 완료 아님")
  @PostMapping("/internal/settlement-quarantine-deliveries/{deliveryId}/resumes")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public QuarantineResponse.Delivery resume(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID deliveryId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody OperationRequests.QuarantineReplay r) {
    return QuarantineResponse.Delivery.from(
        quarantines.resume(operator, deliveryId, key, r.expectedVersion(), r.reasonCode()));
  }
}
