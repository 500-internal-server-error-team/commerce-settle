package com.commercesettle.payment.presentation.controller;

import com.commercesettle.internalauth.InternalCallerFilter;
import com.commercesettle.payment.application.service.QuarantineOperationService;
import com.commercesettle.payment.presentation.dto.PaymentQuarantineResponse;
import com.commercesettle.payment.presentation.dto.ProblemResponse;
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
import jakarta.validation.constraints.Pattern;
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

/** 운영자 전용 격리 원문 조회·원키 재처리·소진 발행 재개. 운영자 자격은 컨트롤러 전의 내부 호출 필터가 명세 x-actor로 확인한다. */
@Tag(
    name = "Payment quarantines",
    description = "운영자가 Payment 격리 원문을 조회하고 원래 topic·key·bytes로 다시 발행한다.")
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
      description = "없는 격리 원문·발행 작업",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "409",
      description = "버전·멱등·발행 상태 충돌",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
public class PaymentQuarantineController {
  private final QuarantineOperationService quarantines;

  public PaymentQuarantineController(QuarantineOperationService quarantines) {
    this.quarantines = quarantines;
  }

  @Operation(operationId = "get_internal_payment_quarantines", summary = "격리 원문 목록")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/internal/payment-quarantines")
  public PaymentQuarantineResponse.Page list(
      @RequestParam(required = false)
          @Pattern(
              regexp = "PENDING|RUNNING|RETRY|PUBLISHED|REVIEW_REQUIRED",
              message = "허용된 발행 상태가 아닙니다.")
          String deliveryStatus,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return PaymentQuarantineResponse.Page.from(quarantines.list(deliveryStatus, page, size));
  }

  @Operation(operationId = "get_internal_payment_quarantines_quarantineId", summary = "격리 원문 조회")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/internal/payment-quarantines/{quarantineId}")
  public PaymentQuarantineResponse get(@PathVariable UUID quarantineId) {
    return PaymentQuarantineResponse.from(quarantines.get(quarantineId));
  }

  @Operation(
      operationId = "post_internal_payment_quarantines_quarantineId_replays",
      summary = "격리 원문 원키 재처리")
  @ApiResponse(responseCode = "202", description = "발행 작업 접수; 업무 반영 완료 아님")
  @PostMapping("/internal/payment-quarantines/{quarantineId}/replays")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public PaymentQuarantineResponse replay(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID quarantineId,
      @Parameter(description = "운영자·작업 범위 멱등 키. 같은 키·본문 재요청은 현재 상태, 다른 본문은 409")
          @RequestHeader("Idempotency-Key")
          @Size(min = 8, max = 128, message = "8자 이상 128자 이하여야 합니다.")
          String key,
      @Valid @RequestBody PaymentQuarantineResponse.ReplayRequest request) {
    return PaymentQuarantineResponse.from(
        quarantines.replay(
            operator, quarantineId, key, request.expectedVersion(), request.reasonCode()));
  }

  @Operation(
      operationId = "post_internal_payment_quarantine_deliveries_deliveryId_resumes",
      summary = "소진 발행 작업 재개")
  @ApiResponse(responseCode = "202", description = "발행 작업 접수; 업무 반영 완료 아님")
  @PostMapping("/internal/payment-quarantine-deliveries/{deliveryId}/resumes")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public PaymentQuarantineResponse.Delivery resume(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID deliveryId,
      @Parameter(description = "운영자·작업 범위 멱등 키. 같은 키·본문 재요청은 현재 상태, 다른 본문은 409")
          @RequestHeader("Idempotency-Key")
          @Size(min = 8, max = 128, message = "8자 이상 128자 이하여야 합니다.")
          String key,
      @Valid @RequestBody PaymentQuarantineResponse.ReplayRequest request) {
    return PaymentQuarantineResponse.Delivery.from(
        quarantines.resume(
            operator, deliveryId, key, request.expectedVersion(), request.reasonCode()));
  }
}
