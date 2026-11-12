package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.internalauth.InternalCallerFilter;
import com.commercesettle.settlement.application.port.InboxOperationStore.Filter;
import com.commercesettle.settlement.application.service.InboxOperationService;
import com.commercesettle.settlement.presentation.dto.InboxOperationResponse;
import com.commercesettle.settlement.presentation.dto.InboxRejudgementRequests;
import com.commercesettle.settlement.presentation.dto.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** OPERATOR 전용 업무 Inbox 관측·고정 대상 재판정. Gateway에는 공개하지 않는다. */
@RestController
@Tag(name = "settlement")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "입력 오류",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemResponse.class))),
  @ApiResponse(responseCode = "401", description = "자격 없음·불일치"),
  @ApiResponse(responseCode = "403", description = "운영자 권한 없음"),
  @ApiResponse(responseCode = "404", description = "대상 없음"),
  @ApiResponse(responseCode = "409", description = "버전·사유·대상·실행 상태·멱등키 충돌"),
  @ApiResponse(responseCode = "503", description = "저장소 일시 장애·잠금 대기 상한 초과")
})
public class SettlementInboxController {
  private final InboxOperationService service;

  public SettlementInboxController(InboxOperationService service) {
    this.service = service;
  }

  @Operation(
      operationId = "get_internal_settlement_inbox_summary",
      summary = "확인 사유·이벤트 종류별 건수와 최장 대기 나이")
  @ApiResponse(responseCode = "200", description = "조회 완료")
  @GetMapping("/internal/settlement-inbox-summary")
  public List<InboxOperationResponse.Summary> summary() {
    return service.summary().stream().map(InboxOperationResponse.Summary::from).toList();
  }

  @Operation(operationId = "get_internal_settlement_inboxes", summary = "업무 Inbox 확인 대상 목록")
  @ApiResponse(responseCode = "200", description = "원문 대신 크기·SHA-256·현재 버전 반환")
  @GetMapping("/internal/settlement-inboxes")
  public InboxOperationResponse.Page<InboxOperationResponse.Item> list(
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String eventType,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime receivedBefore,
      @RequestParam(required = false) UUID inboxId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return InboxOperationResponse.Page.from(
        service.list(
            new Filter(reasonCode, eventType, receivedBefore.toInstant(), inboxId), page, size),
        InboxOperationResponse.Item::from);
  }

  @Operation(operationId = "get_internal_settlement_inboxes_inboxId", summary = "업무 Inbox 단건 요약")
  @ApiResponse(responseCode = "200", description = "조회 완료")
  @GetMapping("/internal/settlement-inboxes/{inboxId}")
  public InboxOperationResponse.Item get(@PathVariable UUID inboxId) {
    return InboxOperationResponse.Item.from(service.get(inboxId));
  }

  @Operation(
      operationId = "post_internal_settlement_inbox_rejudgements",
      summary = "제한된 고정 대상 재판정 실행 생성")
  @ApiResponse(responseCode = "202", description = "실행 접수; 업무 반영 완료 아님")
  @PostMapping("/internal/settlement-inbox-rejudgements")
  public ResponseEntity<InboxOperationResponse.Run> create(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody InboxRejudgementRequests.Create r) {
    var run =
        service.create(
            operator,
            key,
            r.filter().toFilter(),
            r.expectedCount(),
            r.targets().stream().map(InboxRejudgementRequests.Target::toTarget).toList(),
            r.reasonCode());
    return ResponseEntity.accepted()
        .location(URI.create("/internal/settlement-inbox-rejudgements/" + run.id()))
        .body(InboxOperationResponse.Run.from(run));
  }

  @Operation(operationId = "get_internal_settlement_inbox_rejudgements", summary = "재판정 실행 목록")
  @ApiResponse(responseCode = "200", description = "조회 완료")
  @GetMapping("/internal/settlement-inbox-rejudgements")
  public InboxOperationResponse.Page<InboxOperationResponse.Run> runs(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return InboxOperationResponse.Page.from(
        service.runs(page, size), InboxOperationResponse.Run::from);
  }

  @Operation(
      operationId = "get_internal_settlement_inbox_rejudgements_runId",
      summary = "재판정 실행과 진행 상태")
  @ApiResponse(responseCode = "200", description = "조회 완료")
  @GetMapping("/internal/settlement-inbox-rejudgements/{runId}")
  public InboxOperationResponse.Run run(@PathVariable UUID runId) {
    return InboxOperationResponse.Run.from(service.run(runId));
  }

  @Operation(
      operationId = "get_internal_settlement_inbox_rejudgements_runId_targets",
      summary = "고정 대상별 버전·판정 결과")
  @ApiResponse(responseCode = "200", description = "조회 완료")
  @GetMapping("/internal/settlement-inbox-rejudgements/{runId}/targets")
  public InboxOperationResponse.Page<InboxOperationResponse.Target> targets(
      @PathVariable UUID runId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return InboxOperationResponse.Page.from(
        service.targets(runId, page, size), InboxOperationResponse.Target::from);
  }

  @Operation(
      operationId = "get_internal_settlement_inbox_rejudgements_runId_attempts",
      summary = "재판정 시도·임대 만료·중단 복구 이력")
  @ApiResponse(responseCode = "200", description = "조회 완료")
  @GetMapping("/internal/settlement-inbox-rejudgements/{runId}/attempts")
  public InboxOperationResponse.Page<InboxOperationResponse.Attempt> attempts(
      @PathVariable UUID runId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return InboxOperationResponse.Page.from(
        service.attempts(runId, page, size), InboxOperationResponse.Attempt::from);
  }

  @Operation(
      operationId = "get_internal_settlement_inbox_rejudgements_runId_audits",
      summary = "운영자·처리기의 재판정 감사 이력")
  @ApiResponse(responseCode = "200", description = "조회 완료")
  @GetMapping("/internal/settlement-inbox-rejudgements/{runId}/audits")
  public InboxOperationResponse.Page<InboxOperationResponse.Audit> audits(
      @PathVariable UUID runId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return InboxOperationResponse.Page.from(
        service.audits(runId, page, size), InboxOperationResponse.Audit::from);
  }

  @Operation(
      operationId = "post_internal_settlement_inbox_rejudgements_runId_pauses",
      summary = "실행 중단; 고정 대상 유지")
  @ApiResponse(responseCode = "202", description = "중단 상태 반영")
  @PostMapping("/internal/settlement-inbox-rejudgements/{runId}/pauses")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public InboxOperationResponse.Run pause(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID runId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody InboxRejudgementRequests.Transition r) {
    return transition(operator, runId, key, r, "PAUSE");
  }

  @Operation(
      operationId = "post_internal_settlement_inbox_rejudgements_runId_resumes",
      summary = "원래 고정 대상으로 실행 재개")
  @ApiResponse(responseCode = "202", description = "재개 접수")
  @PostMapping("/internal/settlement-inbox-rejudgements/{runId}/resumes")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public InboxOperationResponse.Run resume(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID runId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody InboxRejudgementRequests.Transition r) {
    return transition(operator, runId, key, r, "RESUME");
  }

  @Operation(
      operationId = "post_internal_settlement_inbox_rejudgements_runId_cancellations",
      summary = "미처리 대상 취소; 이미 반영한 결과 보존")
  @ApiResponse(responseCode = "202", description = "취소 상태 반영")
  @PostMapping("/internal/settlement-inbox-rejudgements/{runId}/cancellations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public InboxOperationResponse.Run cancel(
      @Parameter(hidden = true) @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER)
          String operator,
      @PathVariable UUID runId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody InboxRejudgementRequests.Transition r) {
    return transition(operator, runId, key, r, "CANCEL");
  }

  private InboxOperationResponse.Run transition(
      String operator, UUID id, String key, InboxRejudgementRequests.Transition r, String action) {
    return InboxOperationResponse.Run.from(
        service.transition(operator, id, key, r.expectedVersion(), action, r.reasonCode()));
  }
}
