package com.commercesettle.financemock.presentation.controller;

import com.commercesettle.financemock.application.dto.MockExecutionResult;
import com.commercesettle.financemock.application.service.MockPaymentService;
import com.commercesettle.financemock.domain.model.MockOperation;
import com.commercesettle.financemock.domain.model.PaymentProvider;
import com.commercesettle.financemock.presentation.dto.MockApprovalRequest;
import com.commercesettle.financemock.presentation.dto.MockRefundRequest;
import com.commercesettle.financemock.presentation.dto.MockTransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** 내부 Payment 호출용이다. Gateway에 공개하지 않는다. */
@RestController
@RequestMapping("/mock/providers/{providerId}")
class MockPaymentController {
  private final MockPaymentService payments;

  MockPaymentController(MockPaymentService payments) {
    this.payments = payments;
  }

  @Operation(operationId = "post_mock_providers_providerId_payments", summary = "모의 외부 승인")
  @ApiResponse(responseCode = "202", description = "접수 결과의 status로 성공·거절을 구분한다.")
  @ApiResponse(responseCode = "504", description = "결과 커밋 후 응답 유실을 재현한다. 원키로 조회한다.")
  @PostMapping("/payments")
  ResponseEntity<MockTransactionResponse> approve(
      @PathVariable PaymentProvider providerId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody MockApprovalRequest request) {
    return response(
        payments.approve(
            providerId,
            request.businessKey(),
            request.method(),
            request.accountRef(),
            request.fundingAccountRef(),
            request.amount(),
            request.scenarioVersion()));
  }

  @Operation(
      operationId = "post_mock_providers_providerId_payments_externalId_refunds",
      summary = "원외부 결제 전액 환불")
  @ApiResponse(responseCode = "202", description = "접수 결과의 status로 성공·거절을 구분한다.")
  @ApiResponse(responseCode = "504", description = "결과 커밋 후 응답 유실을 재현한다. 원키로 조회한다.")
  @PostMapping("/payments/{externalId}/refunds")
  ResponseEntity<MockTransactionResponse> refund(
      @PathVariable PaymentProvider providerId,
      @PathVariable UUID externalId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody MockRefundRequest request) {
    return response(
        payments.refund(providerId, request.businessKey(), externalId, request.amount()));
  }

  @Operation(
      operationId = "get_mock_providers_providerId_transactions_businessKey",
      summary = "원대역 거래 조회")
  @GetMapping("/transactions/{businessKey}")
  MockTransactionResponse find(
      @PathVariable PaymentProvider providerId,
      @PathVariable UUID businessKey,
      @RequestParam @Schema(allowableValues = {"APPROVE", "REFUND"}) MockOperation operation,
      @RequestParam @NotBlank @Size(max = 32) String scenarioVersion) {
    return MockTransactionResponse.from(
        payments.find(providerId, operation, businessKey, scenarioVersion));
  }

  private ResponseEntity<MockTransactionResponse> response(MockExecutionResult result) {
    if (result.responseLost()) return ResponseEntity.status(504).build();
    var transaction = result.view().transaction();
    var location =
        UriComponentsBuilder.fromPath(
                "/mock/providers/"
                    + transaction.getProviderId()
                    + "/transactions/"
                    + transaction.getBusinessKey())
            .queryParam("operation", transaction.getOperation())
            .queryParam("scenarioVersion", "{version}")
            .encode()
            .buildAndExpand(transaction.getScenarioVersion())
            .toUri();
    return ResponseEntity.accepted()
        .location(location)
        .body(MockTransactionResponse.from(result.view()));
  }
}
