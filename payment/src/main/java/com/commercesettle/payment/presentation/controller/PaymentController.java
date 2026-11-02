package com.commercesettle.payment.presentation.controller;

import com.commercesettle.payment.application.service.PaymentQueryService;
import com.commercesettle.payment.presentation.dto.PaymentPageResponse;
import com.commercesettle.payment.presentation.dto.PaymentResponse;
import com.commercesettle.payment.presentation.dto.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments", description = "구매자 본인의 결제 진행을 조회한다.")
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
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
  private final PaymentQueryService payments;

  public PaymentController(PaymentQueryService payments) {
    this.payments = payments;
  }

  @Operation(operationId = "get_api_payments", summary = "본인 결제 목록")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping
  public PaymentPageResponse list(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size,
      @Parameter(description = "본인 주문의 결제만 조회") @RequestParam(required = false) UUID orderId) {
    return PaymentPageResponse.from(payments.list(buyer.id(), orderId, page, size));
  }

  @Operation(operationId = "get_api_payments_paymentId", summary = "본인 결제 진행")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/{paymentId}")
  public PaymentResponse get(
      @Parameter(hidden = true) AuthenticatedBuyer buyer, @PathVariable UUID paymentId) {
    return PaymentResponse.from(payments.get(buyer.id(), paymentId));
  }
}
