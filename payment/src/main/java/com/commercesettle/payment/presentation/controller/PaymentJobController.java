package com.commercesettle.payment.presentation.controller;

import com.commercesettle.payment.application.service.PaymentJobOperationService;
import com.commercesettle.payment.presentation.dto.JobPageResponse;
import com.commercesettle.payment.presentation.dto.JobResponse;
import com.commercesettle.payment.presentation.dto.ProblemResponse;
import com.commercesettle.payment.presentation.dto.ReplayRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 운영자 전용 결제 작업 조회와 원업무 재처리. 운영자 자격은 컨트롤러 전의 내부 호출 필터가 확인한다. */
@Tag(name = "Payment jobs", description = "운영자가 결제 작업을 조회하고 원업무 키로 재처리한다.")
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
      description = "없는 작업",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "409",
      description = "상태·버전·멱등 충돌",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
@RequestMapping("/internal/payment-jobs")
public class PaymentJobController {
  private final PaymentJobOperationService jobs;

  public PaymentJobController(PaymentJobOperationService jobs) {
    this.jobs = jobs;
  }

  @Operation(operationId = "get_internal_payment_jobs", summary = "운영 작업 목록")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping
  public JobPageResponse list(
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return JobPageResponse.from(jobs.list(page, size));
  }

  @Operation(operationId = "post_internal_payment_jobs_jobId_replays", summary = "원업무 작업 재처리")
  @ApiResponse(responseCode = "202", description = "비동기 접수; 금융 처리 완료 아님")
  @PostMapping("/{jobId}/replays")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public JobResponse replay(
      @Parameter(hidden = true) @RequestAttribute("verifiedInternalCaller") String operator,
      @PathVariable UUID jobId,
      @Parameter(description = "종류+계정ID+작업범위; 동일본문재요청은원리소스, 다른본문409.")
          @RequestHeader("Idempotency-Key")
          @Size(min = 8, max = 128, message = "8자 이상 128자 이하여야 합니다.")
          String key,
      @Valid @RequestBody ReplayRequest request) {
    return JobResponse.from(
        jobs.replay(operator, jobId, key, request.expectedVersion(), request.reason()));
  }
}
