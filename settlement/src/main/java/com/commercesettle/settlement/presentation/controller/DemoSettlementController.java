package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.internalauth.InternalCallerFilter;
import com.commercesettle.settlement.application.service.DemoSettlementService;
import com.commercesettle.settlement.presentation.dto.DemoSettlementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo-settlement-runs")
public class DemoSettlementController {
  private final DemoSettlementService runs;

  public DemoSettlementController(DemoSettlementService runs) {
    this.runs = runs;
  }

  @Operation(operationId = "post_internal_demo_settlement_runs", summary = "시연 회차 정산 실행·재시작")
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public org.springframework.http.ResponseEntity<DemoSettlementResponse> start(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody
          com.commercesettle.settlement.presentation.dto.DemoSettlementStartRequest request) {
    var run = runs.start(operator, key, request.runId());
    return org.springframework.http.ResponseEntity.accepted()
        .location(java.net.URI.create("/internal/demo-settlement-runs/" + run.id()))
        .body(DemoSettlementResponse.from(run, runs.batches(run.id())));
  }

  @Operation(operationId = "get_internal_demo_settlement_run", summary = "시연 정산 회차·지급 결과")
  @GetMapping("/{runId}")
  public DemoSettlementResponse get(@PathVariable UUID runId) {
    return DemoSettlementResponse.from(runs.get(runId), runs.batches(runId));
  }

  @Operation(operationId = "get_internal_demo_settlement_capabilities", summary = "정산 시연 모드 활성 상태")
  @GetMapping("/capabilities")
  public com.commercesettle.settlement.presentation.dto.DemoCapabilitiesResponse capabilities() {
    return new com.commercesettle.settlement.presentation.dto.DemoCapabilitiesResponse(
        runs.enabled());
  }
}
