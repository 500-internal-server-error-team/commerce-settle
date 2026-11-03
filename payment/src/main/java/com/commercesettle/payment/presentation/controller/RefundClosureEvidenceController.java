package com.commercesettle.payment.presentation.controller;

import com.commercesettle.payment.application.port.RefundClosureQuery;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RefundClosureEvidenceController {
  private final RefundClosureQuery query;

  public RefundClosureEvidenceController(RefundClosureQuery query) {
    this.query = query;
  }

  @GetMapping("/internal/refunds/{refundId}/financial-closure-evidence")
  @Operation(
      operationId = "get_internal_payment_refunds_refundId_closure_evidence",
      summary = "Commerce 전용 원환불 종결 증빙 조회")
  public RefundClosureQuery.Evidence get(@PathVariable UUID refundId) {
    return query
        .find(refundId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
