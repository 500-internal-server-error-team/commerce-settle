package com.commercesettle.financemock.presentation.controller;

import com.commercesettle.financemock.application.service.MockReconciliationService;
import com.commercesettle.financemock.presentation.dto.ReconciliationResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MockReconciliationController {
  private final MockReconciliationService service;
  private final ObjectMapper mapper;

  public MockReconciliationController(MockReconciliationService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Operation(operationId = "get_mock_payout_reconciliation", summary = "고정 시점의 지급·실제 차감 증빙 페이지")
  @GetMapping("/mock/payout-reconciliation")
  public ReconciliationResponses.PayoutPage payouts(
      @RequestParam OffsetDateTime cutoffAt,
      @RequestParam(required = false) OffsetDateTime snapshotAt,
      @RequestParam(required = false) @Size(min = 1, max = 2048) String cursor,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
    return mapper.convertValue(
        service.payouts(
            cutoffAt.toInstant(), snapshotAt == null ? null : snapshotAt.toInstant(), cursor, size),
        ReconciliationResponses.PayoutPage.class);
  }

  @org.springframework.web.bind.annotation.PostMapping("/mock/payment-reconciliation-queries")
  public java.util.List<ReconciliationResponses.Transaction> lookup(
      @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
          Query request) {
    return service.lookup(request.businessKeys(), request.snapshotAt().toInstant()).stream()
        .map(row -> mapper.convertValue(row, ReconciliationResponses.Transaction.class))
        .toList();
  }

  public record Query(
      @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 100)
          java.util.List<@jakarta.validation.constraints.NotNull UUID> businessKeys,
      @jakarta.validation.constraints.NotNull OffsetDateTime snapshotAt) {}

  @Operation(
      operationId = "get_mock_payment_reconciliation",
      summary = "Payment 전용 PG·간편결제 원거래 증빙 페이지")
  @GetMapping("/mock/payment-reconciliation")
  public ReconciliationResponses.PaymentPage payments(
      @RequestParam OffsetDateTime cutoffAt,
      @RequestParam(required = false) OffsetDateTime snapshotAt,
      @RequestParam(required = false) @Size(min = 1, max = 2048) String cursor,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
    return mapper.convertValue(
        service.payments(
            cutoffAt.toInstant(), snapshotAt == null ? null : snapshotAt.toInstant(), cursor, size),
        ReconciliationResponses.PaymentPage.class);
  }

  @Operation(
      operationId = "get_mock_reconciliation_integrity",
      summary = "현재 대역 계정·현금·예약 원장 정합성 조회")
  @GetMapping("/mock/reconciliation-integrity")
  public ReconciliationResponses.Integrity integrity() {
    return mapper.convertValue(service.integrity(), ReconciliationResponses.Integrity.class);
  }
}
