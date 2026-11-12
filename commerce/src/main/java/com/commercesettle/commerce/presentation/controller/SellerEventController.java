package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.SaleEventService;
import com.commercesettle.commerce.domain.model.SaleEvent;
import com.commercesettle.commerce.presentation.dto.CreateEventRequest;
import com.commercesettle.commerce.presentation.dto.EventPageResponse;
import com.commercesettle.commerce.presentation.dto.EventResponse;
import com.commercesettle.commerce.presentation.dto.ReasonRequest;
import com.commercesettle.commerce.presentation.dto.UpdateEventRequest;
import com.commercesettle.commerce.presentation.dto.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller events", description = "판매자 본인 선착순 이벤트 관리")
@RestController
@RequestMapping("/api/sellers/me/events")
public class SellerEventController {
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final SaleEventService events;

  public SellerEventController(SaleEventService events) {
    this.events = events;
  }

  @Operation(
      operationId = "post_api_sellers_me_events",
      summary = "이름을 정해 이벤트 등록",
      description = "미공개로 만들며 재고는 공개 때 배정한다. 같은 키·본문의 재요청은 기존 이벤트를 반환한다.")
  @PostMapping
  public ResponseEntity<EventResponse> register(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody CreateEventRequest request) {
    SaleEvent event =
        events.register(
            seller.id(),
            idempotencyKey,
            request.productId(),
            request.name(),
            request.eventPrice(),
            request.quota(),
            request.startsAt().toInstant(),
            request.endsAt().toInstant());
    return ResponseEntity.created(URI.create("/api/sellers/me/events/" + event.getId()))
        .body(EventResponse.from(event));
  }

  @Operation(operationId = "get_api_sellers_me_events", summary = "본인 이벤트 목록")
  @GetMapping
  public EventPageResponse sellerEvents(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return EventPageResponse.from(events.sellerEvents(seller.id(), page, size));
  }

  @Operation(operationId = "get_api_sellers_me_events_eventId", summary = "본인 이벤트 조회")
  @GetMapping("/{eventId}")
  public EventResponse sellerEvent(
      @Parameter(hidden = true) AuthenticatedSeller seller, @PathVariable UUID eventId) {
    return EventResponse.from(events.sellerEvent(seller.id(), eventId));
  }

  @Operation(
      operationId = "put_api_sellers_me_events_eventId",
      summary = "미공개 이벤트 변경",
      description = "공개한 이벤트이거나 expectedVersion이 현재 버전과 다르면 409이다.")
  @PutMapping("/{eventId}")
  public EventResponse change(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID eventId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody UpdateEventRequest request) {
    return EventResponse.from(
        events.change(
            seller.id(),
            eventId,
            idempotencyKey,
            request.productId(),
            request.name(),
            request.eventPrice(),
            request.quota(),
            request.startsAt().toInstant(),
            request.endsAt().toInstant(),
            request.expectedVersion()));
  }

  @Operation(
      operationId = "post_api_sellers_me_events_eventId_publication",
      summary = "이벤트 공개·재고 배정",
      description =
          "판매 중인 상품의 가용 재고에서 한정 수량을 배정한다. 가용 재고 부족은 409 INVENTORY_INSUFFICIENT,"
              + " 같은 상품의 기간 중복은 409 PRODUCT_EVENT_IN_PROGRESS이다.")
  @PostMapping("/{eventId}/publication")
  public EventResponse publish(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID eventId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody VersionRequest request) {
    return EventResponse.from(
        events.publish(seller.id(), eventId, idempotencyKey, request.expectedVersion()));
  }

  @Operation(
      operationId = "post_api_sellers_me_events_eventId_closure",
      summary = "이벤트 조기 종료",
      description = "신규 참여를 막고 판매·결제 대기 예약이 아닌 배정분만 가용 재고로 돌린다. 기존 예약은 해제하지 않는다.")
  @PostMapping("/{eventId}/closure")
  public EventResponse close(
      @Parameter(hidden = true) AuthenticatedSeller seller,
      @PathVariable UUID eventId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody ReasonRequest request) {
    return EventResponse.from(
        events.close(
            seller.id(), eventId, idempotencyKey, request.expectedVersion(), request.reason()));
  }
}
