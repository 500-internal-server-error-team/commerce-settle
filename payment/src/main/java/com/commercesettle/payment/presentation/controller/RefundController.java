package com.commercesettle.payment.presentation.controller;

import com.commercesettle.payment.application.service.RefundQueryService;
import com.commercesettle.payment.presentation.dto.FinancialRefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 구매자 본인의 금융 환불 진행 조회. 없거나 다른 구매자의 환불은 구분하지 않고 404다. 404·401·403은 공통 오류 처리기가 상태별 공통 코드로 바꾼다. */
@Tag(name = "Refunds", description = "구매자 본인의 금융 환불 진행을 조회한다.")
@RestController
@RequestMapping("/api/refunds")
public class RefundController {
  private final RefundQueryService refunds;

  public RefundController(RefundQueryService refunds) {
    this.refunds = refunds;
  }

  @Operation(operationId = "get_api_refunds", summary = "본인 금융 환불 목록")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping
  public FinancialRefundResponse.Page list(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return FinancialRefundResponse.Page.from(refunds.list(buyer.id(), page, size));
  }

  @Operation(operationId = "get_api_refunds_refundId", summary = "본인 금융 환불 진행")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/{refundId}")
  public FinancialRefundResponse get(
      @Parameter(hidden = true) AuthenticatedBuyer buyer, @PathVariable UUID refundId) {
    return refunds
        .get(buyer.id(), refundId)
        .map(FinancialRefundResponse::from)
        .orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND));
  }
}
