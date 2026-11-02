package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.port.NotificationGateway;
import com.commercesettle.commerce.application.port.NotificationStore;
import com.commercesettle.commerce.domain.model.Notification;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Schema(name = "Notification", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record NotificationResponse(
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) Notification.Kind kind,
    @Schema(allowableValues = {"MOCK_KAKAO", "KAKAO_SELF"}) @JsonProperty(required = true)
        String channel,
    @JsonProperty(value = "isMock", required = true) boolean isMock,
    @JsonProperty(required = true) Notification.Status status,
    @Schema(maxLength = 120) @JsonProperty(required = true) String title,
    @Schema(maxLength = 1000) @JsonProperty(required = true) String body,
    @Schema(minimum = "1") @JsonProperty(required = true) int itemCount,
    @JsonProperty(required = true) OffsetDateTime occurredAt,
    @JsonProperty(required = true) OffsetDateTime createdAt,
    @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime sentAt) {
  public static NotificationResponse from(Notification n) {
    return new NotificationResponse(
        n.id(),
        n.kind(),
        n.channel().name(),
        n.channel() == Notification.Channel.MOCK_KAKAO,
        n.status(),
        n.title(),
        n.body(),
        n.itemCount(),
        kst(n.occurredAt()),
        kst(n.createdAt()),
        kst(n.sentAt()));
  }

  @Schema(
      name = "NotificationDelivery",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Delivery(
      @JsonProperty(required = true) NotificationResponse notification,
      @JsonProperty(required = true)
          com.commercesettle.commerce.domain.model.ActorKind recipientKind,
      @JsonProperty(required = true) UUID recipientId,
      @JsonProperty(required = true) String dedupKey,
      @JsonProperty(required = true) int attemptCount,
      @JsonProperty(required = true) int retryCount,
      @JsonProperty(required = true) OffsetDateTime nextRunAt,
      @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime leaseUntil,
      @Schema(nullable = true) @JsonProperty(required = true) String lastErrorCode,
      @JsonProperty(required = true) long version) {
    public static Delivery from(Notification n) {
      return new Delivery(
          NotificationResponse.from(n),
          n.recipientKind(),
          n.recipientId(),
          n.dedupKey(),
          n.attemptCount(),
          n.retryCount(),
          kst(n.nextRunAt()),
          kst(n.leaseUntil()),
          n.lastErrorCode(),
          n.version());
    }
  }

  @Schema(
      name = "NotificationAttempt",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Attempt(
      @JsonProperty(required = true) UUID id,
      @JsonProperty(required = true) UUID deliveryId,
      @JsonProperty(required = true) int attemptNo,
      @JsonProperty(required = true) String operation,
      @JsonProperty(required = true) String status,
      @JsonProperty(required = true) OffsetDateTime startedAt,
      @Schema(nullable = true) @JsonProperty(required = true) OffsetDateTime finishedAt,
      @Schema(nullable = true) @JsonProperty(required = true) String errorCode,
      @Schema(nullable = true) @JsonProperty(required = true) String operatorRef,
      @Schema(nullable = true) @JsonProperty(required = true) String reason) {
    public static Attempt from(NotificationStore.Attempt a) {
      return new Attempt(
          a.id(),
          a.deliveryId(),
          a.attemptNo(),
          a.operation(),
          a.status(),
          kst(a.startedAt()),
          kst(a.finishedAt()),
          a.errorCode(),
          a.operatorRef(),
          a.reason());
    }
  }

  @Schema(
      name = "MockNotificationReceipt",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Receipt(
      @JsonProperty(required = true) UUID deliveryId,
      @JsonProperty(required = true) String requestHash,
      @JsonProperty(required = true) String result,
      @JsonProperty(required = true) OffsetDateTime processedAt,
      @JsonProperty(value = "isMock", required = true) boolean isMock) {
    public static Receipt from(NotificationGateway.Receipt r) {
      return new Receipt(r.deliveryId(), r.requestHash(), r.result(), kst(r.processedAt()), true);
    }
  }

  @Schema(
      name = "NotificationReplayRequest",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Replay(
      @NotNull @Min(0) @JsonProperty(required = true) Long expectedVersion,
      @NotBlank @Size(max = 200) @JsonProperty(required = true) String reason) {}

  @Schema(name = "NotificationPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Page(
      @JsonProperty(required = true) List<NotificationResponse> items,
      @JsonProperty(required = true) int page,
      @JsonProperty(required = true) int size,
      @JsonProperty(required = true) long totalElements,
      @JsonProperty(required = true) long totalPages) {
    public static Page from(PageResult<Notification> p) {
      return new Page(
          p.items().stream().map(NotificationResponse::from).toList(),
          p.page(),
          p.size(),
          p.totalElements(),
          pages(p.totalElements(), p.size()));
    }
  }

  @Schema(
      name = "NotificationDeliveryPage",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record DeliveryPage(
      @JsonProperty(required = true) List<Delivery> items,
      @JsonProperty(required = true) int page,
      @JsonProperty(required = true) int size,
      @JsonProperty(required = true) long totalElements,
      @JsonProperty(required = true) long totalPages) {
    public static DeliveryPage from(PageResult<Notification> p) {
      return new DeliveryPage(
          p.items().stream().map(Delivery::from).toList(),
          p.page(),
          p.size(),
          p.totalElements(),
          pages(p.totalElements(), p.size()));
    }
  }

  @Schema(
      name = "NotificationAttemptPage",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record AttemptPage(
      @JsonProperty(required = true) List<Attempt> items,
      @JsonProperty(required = true) int page,
      @JsonProperty(required = true) int size,
      @JsonProperty(required = true) long totalElements,
      @JsonProperty(required = true) long totalPages) {
    public static AttemptPage from(PageResult<NotificationStore.Attempt> p) {
      return new AttemptPage(
          p.items().stream().map(Attempt::from).toList(),
          p.page(),
          p.size(),
          p.totalElements(),
          pages(p.totalElements(), p.size()));
    }
  }

  private static long pages(long total, int size) {
    return total == 0 ? 0 : 1 + (total - 1) / size;
  }

  private static OffsetDateTime kst(Instant at) {
    return at == null ? null : at.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
  }
}
