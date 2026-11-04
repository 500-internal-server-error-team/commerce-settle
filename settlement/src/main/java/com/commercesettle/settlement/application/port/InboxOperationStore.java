package com.commercesettle.settlement.application.port;

import com.commercesettle.settlement.application.dto.PageView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 업무 Inbox 관측과 고정 대상 실행 저장소. 모든 변경은 호출자 트랜잭션에 참여한다. */
public interface InboxOperationStore {
  record Filter(String reasonCode, String eventType, Instant receivedBefore, UUID inboxId) {}

  record ExpectedTarget(UUID inboxId, long expectedVersion) {}

  record InboxView(
      UUID id,
      UUID eventId,
      String eventType,
      String reasonCode,
      String status,
      long version,
      long bodySize,
      String bodySha256,
      Instant receivedAt,
      Instant updatedAt) {}

  record Summary(String reasonCode, String eventType, long count, double oldestAgeSeconds) {}

  record RunView(
      UUID id,
      String status,
      long version,
      Filter filter,
      int expectedCount,
      String requestedBy,
      String reasonCode,
      Instant createdAt,
      Instant updatedAt,
      long completedCount) {}

  record TargetView(
      UUID id,
      UUID inboxId,
      UUID eventId,
      String eventType,
      String reasonCode,
      long expectedVersion,
      String status,
      String outcome,
      int attempts,
      Instant leaseUntil,
      Long resultVersion,
      String resultReasonCode) {}

  record AttemptView(
      UUID id, UUID targetId, int attempt, String outcome, Instant startedAt, Instant finishedAt) {}

  record AuditView(
      UUID id,
      String actorKind,
      String actorRef,
      String action,
      String aggregateType,
      UUID aggregateId,
      String beforeStatus,
      String afterStatus,
      String reasonCode,
      Instant occurredAt) {}

  /** 소진한 대상은 같은 트랜잭션에서 감사하며 새 임대를 만들지 않는다. */
  record Claim(
      UUID runId,
      UUID targetId,
      UUID token,
      UUID attemptId,
      UUID inboxId,
      long expectedVersion,
      String reasonCode,
      int previousAttempts,
      String previousStatus) {
    public boolean exhausted() {
      return previousAttempts >= 10;
    }
  }

  List<Summary> summary();

  PageView<InboxView> list(Filter filter, int page, int size);

  Optional<InboxView> find(UUID id);

  RunView create(
      Filter filter,
      List<ExpectedTarget> targets,
      int expectedCount,
      String operator,
      String reasonCode);

  Optional<RunView> findRun(UUID id);

  PageView<RunView> runs(int page, int size);

  PageView<TargetView> targets(UUID runId, int page, int size);

  PageView<AttemptView> attempts(UUID runId, int page, int size);

  PageView<AuditView> audits(UUID runId, int page, int size);

  void transition(UUID id, long expectedVersion, String action);

  Optional<Claim> claim(Duration lease);

  boolean lockClaim(Claim claim);

  void finish(Claim claim, String outcome);
}
