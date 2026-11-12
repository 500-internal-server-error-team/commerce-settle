package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.exception.OperationRejectedException;
import com.commercesettle.settlement.application.exception.OperationRejectedException.Reason;
import com.commercesettle.settlement.application.exception.QuarantineNotFoundException;
import com.commercesettle.settlement.application.port.QuarantineStore;
import com.commercesettle.settlement.application.port.QuarantineStore.Delivery;
import com.commercesettle.settlement.application.port.QuarantineStore.Source;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원문 격리와 DLT 발행 작업을 원자 저장하고, 운영 재처리(REPLAY)와 소진 발행 재개를 요청자·사유와 함께 기록한다. Kafka 응답은 트랜잭션 밖에서 기다린다. 운영
 * 호출은 {@link QuarantineOperationService}를 거친다.
 */
@Service
public class QuarantineService {
  private final QuarantineStore store;
  private final Clock clock;

  public QuarantineService(QuarantineStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional
  public UUID capture(Source source) {
    try {
      return store.capture(source, "SETTLEMENT_EVENT_INVALID", clock.instant());
    } catch (DataAccessException exception) {
      // DB 제약 오류의 failing-row에 포함될 수 있는 원문을 Listener 로그로 넘기지 않는다.
      throw new IllegalStateException("Quarantine persistence failed");
    }
  }

  /**
   * 인증된 운영 호출자가 원인 수정 뒤 요청한다. 발행 완료는 업무 적용 성공과 다르다. 거절하면 없는 원문은 {@link
   * QuarantineNotFoundException}, 버전 불일치·진행 중 재처리는 {@link OperationRejectedException}이다.
   */
  @Transactional
  public UUID requestReplay(
      UUID quarantineId, long expectedVersion, String operator, String reasonCode) {
    validateOperatorRequest(quarantineId, expectedVersion, operator, reasonCode);
    return store
        .requestReplay(quarantineId, expectedVersion, operator, reasonCode, clock.instant())
        .orElseThrow(
            () -> {
              QuarantineStore.QuarantineView current =
                  store.find(quarantineId).orElseThrow(QuarantineNotFoundException::new);
              return new OperationRejectedException(
                  current.version() != expectedVersion
                      ? Reason.VERSION_CONFLICT
                      : Reason.QUARANTINE_REPLAY_ACTIVE);
            });
  }

  /** 소진된 동일 발행 작업을 재개하며 이전 시도 수와 요청자·사유를 별도 이력에 보존한다. */
  @Transactional
  public UUID resumeDelivery(
      UUID deliveryId, long expectedVersion, String operator, String reasonCode) {
    validateOperatorRequest(deliveryId, expectedVersion, operator, reasonCode);
    return store
        .resumeDelivery(deliveryId, expectedVersion, operator, reasonCode, clock.instant())
        .orElseThrow(
            () -> {
              QuarantineStore.DeliveryView current =
                  store.findDelivery(deliveryId).orElseThrow(QuarantineNotFoundException::new);
              return new OperationRejectedException(
                  current.version() != expectedVersion
                      ? Reason.VERSION_CONFLICT
                      : Reason.QUARANTINE_DELIVERY_NOT_RESUMABLE);
            });
  }

  private static void validateOperatorRequest(
      UUID id, long expectedVersion, String operator, String reasonCode) {
    if (id == null
        || expectedVersion < 0
        || operator == null
        || !operator.matches("[A-Za-z0-9._@:-]{1,100}")
        || reasonCode == null
        || !reasonCode.matches("[A-Z][A-Z0-9_]{0,79}")) {
      throw new IllegalArgumentException("Invalid replay request");
    }
  }

  @Transactional
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts) {
    if (limit < 1 || limit > 100 || leaseMs < 1 || maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("Invalid quarantine delivery limits");
    }
    return store.claim(limit, leaseMs, maxAttempts, clock.instant());
  }

  @Transactional
  public boolean published(Delivery delivery) {
    return store.published(delivery, clock.instant());
  }

  @Transactional
  public boolean failed(Delivery delivery) {
    return store.failed(delivery, clock.instant());
  }
}
