package com.commercesettle.payment.presentation.controller;

import com.commercesettle.payment.application.service.PaymentQueryService;
import com.commercesettle.payment.application.service.ReconciliationQueryService;
import com.commercesettle.payment.presentation.dto.PaymentReconciliationEventPageResponse;
import com.commercesettle.payment.presentation.dto.ProblemResponse;
import com.commercesettle.payment.presentation.dto.SettlementContextResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Settlement 서비스 자격으로만 부르는 읽기 전용 계약. 발신자 판정은 컨트롤러 전의 내부 호출 필터가 명세 x-actor로 한다. */
@Tag(name = "Settlement contracts", description = "Settlement가 정산·대조에 쓰는 Payment 읽기 전용 계약.")
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
      description = "없는 결제",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(
      responseCode = "409",
      description = "확정 전 결제·준비 전 마감",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class)))
})
@RestController
public class SettlementContractController {
  private final PaymentQueryService payments;
  private final ReconciliationQueryService reconciliation;

  public SettlementContractController(
      PaymentQueryService payments, ReconciliationQueryService reconciliation) {
    this.payments = payments;
    this.reconciliation = reconciliation;
  }

  @Operation(
      operationId = "get_internal_payments_paymentId_settlement_context",
      summary = "확정 결제·원금액 컨텍스트")
  @ApiResponse(responseCode = "200", description = "조회·처리 완료")
  @GetMapping("/internal/payments/{paymentId}/settlement-context")
  public SettlementContextResponse settlementContext(@PathVariable UUID paymentId) {
    return SettlementContextResponse.from(payments.settlementContext(paymentId));
  }

  @Operation(
      operationId = "get_internal_payment_reconciliation_events",
      summary = "마감 시점의 금융 이벤트·발행 위치 조회")
  @ApiResponse(responseCode = "200", description = "고정 기준선의 금융 이벤트와 최신 확인 발행 위치")
  @GetMapping("/internal/payment-reconciliation-events")
  public PaymentReconciliationEventPageResponse reconciliationEvents(
      @Parameter(description = "정산 회차의 고정 마감 시각") @RequestParam OffsetDateTime cutoffAt,
      @Parameter(description = "직전 응답의 불투명 nextCursor")
          @RequestParam(required = false)
          @Size(min = 1, max = 2048, message = "1자 이상 2048자 이하여야 합니다.")
          String cursor,
      @RequestParam(defaultValue = "100")
          @Min(value = 1, message = "1 이상이어야 합니다.")
          @Max(value = 100, message = "100 이하여야 합니다.")
          int size) {
    return PaymentReconciliationEventPageResponse.from(
        reconciliation.read(cutoffAt.toInstant(), cursor, size));
  }
}
