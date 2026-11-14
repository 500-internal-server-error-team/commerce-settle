package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.DemoRunService;
import com.commercesettle.commerce.presentation.dto.DemoRunResponse;
import com.commercesettle.commerce.presentation.dto.DemoStageResponse;
import com.commercesettle.internalauth.InternalCallerFilter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo-runs")
public class DemoRunController {
  private final DemoRunService runs;

  public DemoRunController(DemoRunService runs) {
    this.runs = runs;
  }

  @Operation(operationId = "post_internal_demo_runs", summary = "시연 회차 생성")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public org.springframework.http.ResponseEntity<DemoRunResponse> create(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody com.commercesettle.commerce.presentation.dto.DemoRunOptionsRequest o) {
    var result =
        DemoRunResponse.from(
            runs.create(
                operator,
                key,
                o.sellerId(),
                o.productId(),
                o.eventId(),
                o.deliverySeconds(),
                o.refundSeconds()));
    return org.springframework.http.ResponseEntity.created(
            java.net.URI.create("/internal/demo-runs/" + result.id()))
        .body(result);
  }

  @Operation(operationId = "get_internal_demo_run", summary = "시연 회차와 준비 상태 조회")
  @GetMapping("/{runId}")
  public DemoRunResponse get(@PathVariable UUID runId) {
    return DemoRunResponse.from(runs.view(runId));
  }

  @Operation(operationId = "get_internal_demo_run_settlement_context", summary = "정산 서비스용 시연 회차 조회")
  @GetMapping("/{runId}/settlement-context")
  public DemoRunResponse context(@PathVariable UUID runId) {
    return DemoRunResponse.from(runs.view(runId));
  }

  @Operation(operationId = "get_internal_demo_capabilities", summary = "시연 모드 활성 상태")
  @GetMapping("/capabilities")
  public com.commercesettle.commerce.presentation.dto.DemoCapabilitiesResponse capabilities() {
    return new com.commercesettle.commerce.presentation.dto.DemoCapabilitiesResponse(
        runs.enabled());
  }

  @Operation(operationId = "post_internal_demo_run_freeze", summary = "시연 회차 대상 고정")
  @PostMapping("/{runId}/freeze")
  public DemoRunResponse freeze(
      @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER) String operator,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String key,
      @PathVariable UUID runId) {
    return DemoRunResponse.from(runs.freeze(operator, key, runId));
  }

  @Operation(operationId = "post_internal_demo_run_shipments", summary = "시연 회차 발송 시작")
  @PostMapping("/{runId}/shipments")
  public DemoStageResponse ship(
      @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER) String operator,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String key,
      @PathVariable UUID runId,
      @RequestParam(required = false) UUID after) {
    return DemoStageResponse.from(runs.stage(operator, key, runId, true, after));
  }

  @Operation(operationId = "post_internal_demo_run_processing", summary = "도래한 시연 기한 처리")
  @PostMapping("/{runId}/processing")
  public DemoStageResponse process(
      @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER) String operator,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String key,
      @PathVariable UUID runId,
      @RequestParam(required = false) UUID after) {
    return DemoStageResponse.from(runs.stage(operator, key, runId, false, after));
  }
}
