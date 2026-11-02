package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.CouponIssueRunAdminService;
import com.commercesettle.commerce.domain.model.CouponIssueRunStatus;
import com.commercesettle.commerce.presentation.dto.CouponIssueRunPageResponse;
import com.commercesettle.commerce.presentation.dto.CouponIssueRunReplacementRequest;
import com.commercesettle.commerce.presentation.dto.CouponIssueRunResponse;
import com.commercesettle.internalauth.InternalCallerFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 월간 혜택 회차 운영 조회와 집계 오류 교정. 내부 호출 자격 판정이 OPERATOR 서비스 자격만 통과시키며 Gateway에 공개하지 않는다. */
@Tag(name = "Coupon issue runs", description = "운영자 전용 월간 혜택 회차")
@RestController
@RequestMapping("/internal/coupon-issue-runs")
public class CouponIssueRunController {
  private final CouponIssueRunAdminService runs;

  public CouponIssueRunController(CouponIssueRunAdminService runs) {
    this.runs = runs;
  }

  @Operation(
      operationId = "get_internal_coupon_issue_runs",
      summary = "월간 혜택 회차 운영 조회",
      description = "생성 시각 DESC, id DESC. targetMonth는 월 첫날만 허용하며 회차와 발급 건수는 같은 조회 스냅샷에서 반환한다.")
  @GetMapping
  public CouponIssueRunPageResponse runs(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])-01$")
          String targetMonth,
      @RequestParam(required = false) CouponIssueRunStatus status) {
    return CouponIssueRunPageResponse.from(
        runs.runs(targetMonth == null ? null : LocalDate.parse(targetMonth), status, page, size));
  }

  @Operation(operationId = "get_internal_coupon_issue_run", summary = "월간 혜택 회차와 교정 결과 조회")
  @GetMapping("/{runId}")
  public CouponIssueRunResponse run(@PathVariable UUID runId) {
    return CouponIssueRunResponse.from(runs.run(runId));
  }

  @Operation(
      operationId = "post_internal_coupon_issue_run_replacement",
      summary = "발급 전 오류 회차 취소와 대체 회차 생성",
      description =
          "원회차 취소·같은 월/마감/정책의 새 PREPARING 회차·운영자 감사·멱등 결과를 한 트랜잭션에 저장한다. 201은 교정 리소스 생성 완료이며 쿠폰 발급은 이후 스케줄러가 시작한다.")
  @PostMapping("/{runId}/replacements")
  public ResponseEntity<CouponIssueRunResponse> replace(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID runId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody CouponIssueRunReplacementRequest request) {
    CouponIssueRunResponse response =
        CouponIssueRunResponse.from(
            runs.replace(
                operator, runId, idempotencyKey, request.expectedVersion(), request.reasonCode()));
    return ResponseEntity.created(URI.create("/internal/coupon-issue-runs/" + response.id()))
        .body(response);
  }
}
