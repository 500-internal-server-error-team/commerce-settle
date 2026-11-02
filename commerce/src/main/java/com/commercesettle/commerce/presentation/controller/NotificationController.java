package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.NotificationService;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.presentation.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {
  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping("/api/notifications")
  @Operation(operationId = "get_api_notifications", summary = "본인 거래 알림 목록")
  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  public NotificationResponse.Page list(
      @Parameter(hidden = true) AuthenticatedActor actor,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return NotificationResponse.Page.from(service.own(actor.kind(), actor.id(), page, size));
  }

  @GetMapping("/api/notifications/{notificationId}")
  @Operation(operationId = "get_api_notifications_notificationId", summary = "본인 거래 알림 조회")
  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  public NotificationResponse get(
      @Parameter(hidden = true) AuthenticatedActor actor, @PathVariable UUID notificationId) {
    return NotificationResponse.from(service.own(actor.kind(), actor.id(), notificationId));
  }

  @GetMapping("/internal/notification-deliveries")
  @Operation(operationId = "get_internal_notification_deliveries", summary = "모의 알림 발송 목록")
  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  public NotificationResponse.DeliveryPage deliveries(
      @RequestParam(required = false) Notification.Status status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return NotificationResponse.DeliveryPage.from(service.deliveries(status, page, size));
  }

  @GetMapping("/internal/notification-deliveries/{deliveryId}")
  @Operation(
      operationId = "get_internal_notification_deliveries_deliveryId",
      summary = "모의 알림 발송 조회")
  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  public NotificationResponse.Delivery delivery(@PathVariable UUID deliveryId) {
    return NotificationResponse.Delivery.from(service.find(deliveryId));
  }

  @GetMapping("/internal/notification-deliveries/{deliveryId}/attempts")
  @Operation(
      operationId = "get_internal_notification_deliveries_deliveryId_attempts",
      summary = "발송·조회·재처리 이력")
  @ApiResponse(responseCode = "200", description = "조회 완료", useReturnTypeSchema = true)
  public NotificationResponse.AttemptPage attempts(
      @PathVariable UUID deliveryId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return NotificationResponse.AttemptPage.from(service.attempts(deliveryId, page, size));
  }

  @PostMapping("/internal/notification-deliveries/{deliveryId}/replays")
  @Operation(
      operationId = "post_internal_notification_deliveries_deliveryId_replays",
      summary = "원알림 재처리")
  @ApiResponse(responseCode = "202", description = "재처리 접수", useReturnTypeSchema = true)
  public ResponseEntity<NotificationResponse.Delivery> replay(
      @Parameter(hidden = true) @RequestAttribute("verifiedInternalCaller") String operator,
      @PathVariable UUID deliveryId,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody NotificationResponse.Replay request) {
    var n = service.replay(operator, deliveryId, key, request.expectedVersion(), request.reason());
    return ResponseEntity.accepted()
        .location(URI.create("/internal/notification-deliveries/" + deliveryId))
        .body(NotificationResponse.Delivery.from(n));
  }

  @GetMapping("/internal/notification-mock/receipts/{deliveryId}")
  @Operation(
      operationId = "get_internal_notification_mock_receipts_deliveryId",
      summary = "모의 수신 원결과 조회")
  @ApiResponse(responseCode = "200", description = "원결과 조회", useReturnTypeSchema = true)
  public NotificationResponse.Receipt receipt(@PathVariable UUID deliveryId) {
    return NotificationResponse.Receipt.from(service.receipt(deliveryId));
  }
}
