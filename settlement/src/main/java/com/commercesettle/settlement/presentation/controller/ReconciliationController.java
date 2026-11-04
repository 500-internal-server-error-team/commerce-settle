package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.internalauth.InternalCallerFilter;
import com.commercesettle.settlement.application.port.ReconciliationStore;
import com.commercesettle.settlement.application.service.ReconciliationService;
import com.commercesettle.settlement.presentation.dto.ReconciliationResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {
  private final ReconciliationService service;
  private final ReconciliationStore store;
  private final ObjectMapper mapper;

  public ReconciliationController(
      ReconciliationService service, ReconciliationStore store, ObjectMapper mapper) {
    this.service = service;
    this.store = store;
    this.mapper = mapper;
  }

  public record ReconciliationRequest(@NotNull OffsetDateTime cutoffAt) {}

  @Operation(
      operationId = "post_internal_settlement_reconciliations",
      summary = "고정 마감 대조 회차 생성·재개")
  @PostMapping("/internal/settlement-reconciliations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public org.springframework.http.ResponseEntity<ReconciliationResponses.Run> request(
      @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER) String actor,
      @Valid @RequestBody ReconciliationRequest request) {
    UUID id = service.request(request.cutoffAt().toInstant(), actor);
    return org.springframework.http.ResponseEntity.accepted()
        .location(java.net.URI.create("/internal/settlement-reconciliations/" + id))
        .body(mapper.convertValue(store.get(id), ReconciliationResponses.Run.class));
  }

  @Operation(operationId = "get_internal_settlement_reconciliations_runId", summary = "대조 회차 진행 조회")
  @GetMapping("/internal/settlement-reconciliations/{runId}")
  public ReconciliationResponses.Run get(@PathVariable UUID runId) {
    return mapper.convertValue(store.get(runId), ReconciliationResponses.Run.class);
  }

  @Operation(
      operationId = "get_internal_settlement_reconciliations_runId_results",
      summary = "원천별 결과·금액·참조 검사 조회")
  @GetMapping("/internal/settlement-reconciliations/{runId}/results")
  public ReconciliationResponses.Results results(
      @PathVariable UUID runId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return mapper.convertValue(
        store.results(runId, page, size), ReconciliationResponses.Results.class);
  }

  @Operation(
      operationId = "get_internal_settlement_reconciliations_runId_history",
      summary = "최근 100건의 대조 실행·중단·재개·판정 이력")
  @GetMapping("/internal/settlement-reconciliations/{runId}/history")
  public List<ReconciliationResponses.History> history(@PathVariable UUID runId) {
    return store.history(runId).stream()
        .map(row -> mapper.convertValue(row, ReconciliationResponses.History.class))
        .toList();
  }
}
