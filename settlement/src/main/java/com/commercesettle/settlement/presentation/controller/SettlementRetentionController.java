package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.settlement.application.service.SettlementRetentionService;
import com.commercesettle.settlement.presentation.dto.SettlementRetentionLookup;
import com.commercesettle.settlement.presentation.dto.SettlementRetentionResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettlementRetentionController {
  private final SettlementRetentionService service;

  public SettlementRetentionController(SettlementRetentionService service) {
    this.service = service;
  }

  @Operation(operationId = "post_internal_settlement_retention", summary = "알림 보존용 배치 종결 조회")
  @PostMapping("/internal/settlement-retention")
  public List<SettlementRetentionResponse> lookup(
      @Valid @RequestBody SettlementRetentionLookup request) {
    return service.lookup(new java.util.HashSet<>(request.batchIds())).stream()
        .map(SettlementRetentionResponse::from)
        .toList();
  }
}
