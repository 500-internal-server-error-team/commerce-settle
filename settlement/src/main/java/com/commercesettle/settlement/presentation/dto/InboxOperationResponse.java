package com.commercesettle.settlement.presentation.dto;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.port.InboxOperationStore;
import com.commercesettle.settlement.domain.model.InboxReviewReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 업무 Inbox 응답에는 원문·payload·메시지 키를 포함하지 않는다. 시각은 KST다. */
public final class InboxOperationResponse {
  private InboxOperationResponse() {}

  private static OffsetDateTime time(Instant value) {
    return value == null ? null : value.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
  }

  @Schema(name = "SettlementInbox")
  public record Item(
      UUID id,
      UUID eventId,
      String eventType,
      String reasonCode,
      String category,
      String status,
      long version,
      long bodySize,
      String bodySha256,
      OffsetDateTime receivedAt,
      OffsetDateTime updatedAt) {
    public static Item from(InboxOperationStore.InboxView v) {
      return new Item(
          v.id(),
          v.eventId(),
          v.eventType(),
          v.reasonCode(),
          InboxReviewReason.categoryOf(v.reasonCode()).name(),
          v.status(),
          v.version(),
          v.bodySize(),
          v.bodySha256(),
          time(v.receivedAt()),
          time(v.updatedAt()));
    }
  }

  @Schema(name = "SettlementInboxSummary")
  public record Summary(
      String reasonCode, String category, String eventType, long count, double oldestAgeSeconds) {
    public static Summary from(InboxOperationStore.Summary v) {
      return new Summary(
          v.reasonCode(),
          InboxReviewReason.categoryOf(v.reasonCode()).name(),
          v.eventType(),
          v.count(),
          v.oldestAgeSeconds());
    }
  }

  @Schema(name = "InboxRejudgementRun")
  public record Run(
      UUID id,
      String status,
      long version,
      InboxRejudgementRequests.Filter filter,
      int expectedCount,
      long completedCount,
      String requestedBy,
      String reasonCode,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    public static Run from(InboxOperationStore.RunView v) {
      var f = v.filter();
      return new Run(
          v.id(),
          v.status(),
          v.version(),
          new InboxRejudgementRequests.Filter(
              f.reasonCode(), f.eventType(), time(f.receivedBefore()), f.inboxId()),
          v.expectedCount(),
          v.completedCount(),
          v.requestedBy(),
          v.reasonCode(),
          time(v.createdAt()),
          time(v.updatedAt()));
    }
  }

  @Schema(name = "InboxRejudgementTarget")
  public record Target(
      UUID id,
      UUID inboxId,
      UUID eventId,
      String eventType,
      String reasonCode,
      long expectedVersion,
      String status,
      String outcome,
      int attempts,
      OffsetDateTime leaseUntil,
      Long resultVersion,
      String resultReasonCode) {
    public static Target from(InboxOperationStore.TargetView v) {
      return new Target(
          v.id(),
          v.inboxId(),
          v.eventId(),
          v.eventType(),
          v.reasonCode(),
          v.expectedVersion(),
          v.status(),
          v.outcome(),
          v.attempts(),
          time(v.leaseUntil()),
          v.resultVersion(),
          v.resultReasonCode());
    }
  }

  @Schema(name = "InboxRejudgementAttempt")
  public record Attempt(
      UUID id,
      UUID targetId,
      int attempt,
      String outcome,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {
    public static Attempt from(InboxOperationStore.AttemptView v) {
      return new Attempt(
          v.id(),
          v.targetId(),
          v.attempt(),
          v.outcome(),
          time(v.startedAt()),
          time(v.finishedAt()));
    }
  }

  @Schema(name = "InboxRejudgementAudit")
  public record Audit(
      UUID id,
      String actorKind,
      String actorRef,
      String action,
      String aggregateType,
      UUID aggregateId,
      String beforeStatus,
      String afterStatus,
      String reasonCode,
      OffsetDateTime occurredAt) {
    public static Audit from(InboxOperationStore.AuditView v) {
      return new Audit(
          v.id(),
          v.actorKind(),
          v.actorRef(),
          v.action(),
          v.aggregateType(),
          v.aggregateId(),
          v.beforeStatus(),
          v.afterStatus(),
          v.reasonCode(),
          time(v.occurredAt()));
    }
  }

  public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <V, T> Page<T> from(PageView<V> p, Function<V, T> convert) {
      return new Page<>(
          p.items().stream().map(convert).toList(),
          p.page(),
          p.size(),
          p.totalElements(),
          p.totalPages());
    }
  }
}
