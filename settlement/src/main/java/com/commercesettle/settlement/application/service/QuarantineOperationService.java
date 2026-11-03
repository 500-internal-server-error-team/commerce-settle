package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.dto.PageView;
import com.commercesettle.settlement.application.exception.QuarantineNotFoundException;
import com.commercesettle.settlement.application.port.AuditLog;
import com.commercesettle.settlement.application.port.QuarantineStore;
import com.commercesettle.settlement.application.port.QuarantineStore.DeliveryView;
import com.commercesettle.settlement.application.port.QuarantineStore.QuarantineView;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자의 격리 원문 조회와 원키 재처리·소진 발행 재개. 상태 전이는 {@link QuarantineService}가 맡고, 여기서는 멱등 기록과 감사를 같은 트랜잭션에
 * 묶는다. 발행 접수는 Kafka 발행 완료나 업무 반영 성공이 아니다.
 */
@Service
public class QuarantineOperationService {
  static final String REPLAY = "QUARANTINE_REPLAY";
  static final String RESUME = "QUARANTINE_DELIVERY_RESUME";
  private static final Set<String> DELIVERY_STATUSES =
      Set.of("PENDING", "RUNNING", "RETRY", "PUBLISHED", "REVIEW_REQUIRED");

  private final QuarantineService quarantines;
  private final QuarantineStore store;
  private final IdempotentRequests idempotent;
  private final AuditLog audit;
  private final Clock clock;

  public QuarantineOperationService(
      QuarantineService quarantines,
      QuarantineStore store,
      IdempotentRequests idempotent,
      AuditLog audit,
      Clock clock) {
    this.quarantines = quarantines;
    this.store = store;
    this.idempotent = idempotent;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageView<QuarantineView> list(String deliveryStatus, int page, int size) {
    if (page < 0
        || size < 1
        || size > 100
        || (deliveryStatus != null && !DELIVERY_STATUSES.contains(deliveryStatus))) {
      throw new IllegalArgumentException("Invalid page");
    }
    return store.findPage(deliveryStatus, page, size);
  }

  @Transactional(readOnly = true)
  public QuarantineView get(UUID quarantineId) {
    return store.find(quarantineId).orElseThrow(QuarantineNotFoundException::new);
  }

  /** 같은 키·본문의 재요청은 원문의 현재 상태를 돌려주고 REPLAY를 다시 만들지 않는다. */
  @Transactional
  public QuarantineView replay(
      String operator, UUID quarantineId, String key, long expectedVersion, String reasonCode) {
    return idempotent.execute(
        new IdempotentRequests.Request(
            "OPERATOR", operator, REPLAY, key, List.of(quarantineId, expectedVersion, reasonCode)),
        202,
        () -> {
          UUID delivery =
              quarantines.requestReplay(quarantineId, expectedVersion, operator, reasonCode);
          record(
              operator,
              AuditLog.Action.QUARANTINE_REPLAY_REQUESTED,
              "INBOX_QUARANTINE_DELIVERY",
              delivery,
              null,
              reasonCode,
              quarantineId);
          return get(quarantineId);
        },
        QuarantineView::id,
        this::get);
  }

  /** 같은 키·본문의 재요청은 작업의 현재 상태를 돌려주고 다시 재개하지 않는다. */
  @Transactional
  public DeliveryView resume(
      String operator, UUID deliveryId, String key, long expectedVersion, String reasonCode) {
    return idempotent.execute(
        new IdempotentRequests.Request(
            "OPERATOR", operator, RESUME, key, List.of(deliveryId, expectedVersion, reasonCode)),
        202,
        () -> {
          quarantines.resumeDelivery(deliveryId, expectedVersion, operator, reasonCode);
          DeliveryView resumed = delivery(deliveryId);
          record(
              operator,
              AuditLog.Action.QUARANTINE_DELIVERY_RESUMED,
              "INBOX_QUARANTINE_DELIVERY",
              deliveryId,
              "REVIEW_REQUIRED",
              reasonCode,
              resumed.quarantineId());
          return resumed;
        },
        DeliveryView::id,
        this::delivery);
  }

  private DeliveryView delivery(UUID deliveryId) {
    return store.findDelivery(deliveryId).orElseThrow(QuarantineNotFoundException::new);
  }

  /** 같은 원문의 DLT·재처리·재개 감사를 묶어 볼 수 있게 격리 ID로 추적한다. */
  private void record(
      String operator,
      AuditLog.Action action,
      String aggregateType,
      UUID aggregateId,
      String beforeStatus,
      String reasonCode,
      UUID quarantineId) {
    audit.record(
        new AuditLog.Entry(
            "OPERATOR",
            operator,
            action,
            aggregateType,
            aggregateId,
            beforeStatus,
            "PENDING",
            reasonCode,
            quarantineId.toString(),
            clock.instant()));
  }
}
