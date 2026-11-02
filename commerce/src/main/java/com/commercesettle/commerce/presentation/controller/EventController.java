package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.SaleEventService;
import com.commercesettle.commerce.presentation.dto.EventGaugeResponse;
import com.commercesettle.commerce.presentation.dto.EventPageResponse;
import com.commercesettle.commerce.presentation.dto.EventResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Events", description = "구매자·비로그인 사용자의 공개 이벤트 조회")
@RestController
@RequestMapping("/api/events")
public class EventController {
  private final SaleEventService events;

  public EventController(SaleEventService events) {
    this.events = events;
  }

  @Operation(operationId = "get_api_events", summary = "공개 이벤트 목록", description = "미공개 이벤트를 제외한다.")
  @GetMapping
  public EventPageResponse publishedEvents(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return EventPageResponse.from(events.publishedEvents(page, size));
  }

  @Operation(
      operationId = "get_api_events_eventId",
      summary = "공개 이벤트 조회",
      description = "미공개 이벤트는 404로 응답한다.")
  @GetMapping("/{eventId}")
  public EventResponse publishedEvent(@PathVariable UUID eventId) {
    return EventResponse.from(events.publishedEvent(eventId));
  }

  @Operation(
      operationId = "get_api_events_eventId_gauge",
      summary = "판매 진행률·신청 상태",
      description = "DB의 현재 판매·예약 수량과 서버 시각으로 계산하며 asOf는 계산 시각이다.")
  @GetMapping("/{eventId}/gauge")
  public EventGaugeResponse gauge(@PathVariable UUID eventId) {
    return EventGaugeResponse.from(events.gauge(eventId));
  }
}
