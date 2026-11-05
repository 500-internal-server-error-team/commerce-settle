package com.commercesettle.settlement.presentation.controller;

import com.commercesettle.settlement.application.port.EventRecordReader.Entry;
import com.commercesettle.settlement.application.service.EventRecordQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내부 OPERATOR 관측. 일반 페이지 목록이나 금융 성공 응답이 아니다. */
@RestController
public class EventRecordController {
  private final EventRecordQueryService service;

  public EventRecordController(EventRecordQueryService service) {
    this.service = service;
  }

  @Operation(
      operationId = "get_internal_settlement_event_snapshot",
      summary = "거래·이벤트 ID로 자기 DB의 Outbox·Inbox 관측")
  @GetMapping("/internal/settlement-event-snapshot")
  public Snapshot get(
      @Parameter(
              description = "조회할 UUID 1~20개, 쉼표로 구분",
              style = ParameterStyle.FORM,
              explode = Explode.FALSE)
          @RequestParam
          @Size(min = 1, max = 20)
          List<@NotNull UUID> ids) {
    var snapshot = service.get(ids);
    return new Snapshot(
        snapshot.records().stream().map(Record::from).toList(),
        snapshot.limit(),
        snapshot.truncated(),
        kst(snapshot.observedAt()));
  }

  public record Snapshot(
      List<Record> records, int limit, boolean truncated, OffsetDateTime observedAt) {}

  public record Record(
      String kind,
      UUID id,
      UUID eventId,
      String eventType,
      UUID aggregateId,
      String status,
      String topic,
      String consumer,
      Integer partition,
      Long offset,
      int attempts,
      String lastErrorCode,
      UUID resultRef,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<UUID> referenceIds) {
    static Record from(Entry e) {
      return new Record(
          e.kind(),
          e.id(),
          e.eventId(),
          e.eventType(),
          e.aggregateId(),
          e.status(),
          e.topic(),
          e.consumer(),
          e.partition(),
          e.offset(),
          e.attempts(),
          e.lastErrorCode(),
          e.resultRef(),
          kst(e.createdAt()),
          kst(e.updatedAt()),
          e.referenceIds());
    }
  }

  private static OffsetDateTime kst(java.time.Instant value) {
    return value.atOffset(ZoneOffset.ofHours(9));
  }
}
