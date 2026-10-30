package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.port.QuarantineStore;
import com.commercesettle.payment.application.port.QuarantineStore.Delivery;
import com.commercesettle.payment.application.port.QuarantineStore.Source;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원문 격리와 발행 작업만 원자 저장한다. Kafka 응답은 트랜잭션 밖에서 기다린다. */
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
      return store.capture(source, "PAYMENT_EVENT_INVALID", clock.instant());
    } catch (DataAccessException exception) {
      // DB 제약 오류의 failing-row에 포함될 수 있는 원문을 Listener 로그로 넘기지 않는다.
      throw new IllegalStateException("Quarantine persistence failed");
    }
  }

  /** 인증된 운영 호출자가 원인 수정 뒤 요청한다. 발행 완료는 업무 적용 성공과 다르다. */
  @Transactional
  public UUID requestReplay(UUID id, long expectedVersion, String operator, String reasonCode) {
    validateOperatorRequest(id, expectedVersion, operator, reasonCode);
    return store
        .requestReplay(id, expectedVersion, operator, reasonCode, clock.instant())
        .orElseThrow(() -> new IllegalStateException("Quarantine version or replay state changed"));
  }

  /** 소진된 동일 발행 작업을 재개하며 이전 시도 수와 요청자·사유를 별도 이력에 보존한다. */
  @Transactional
  public UUID resumeDelivery(UUID id, long expectedVersion, String operator, String reasonCode) {
    validateOperatorRequest(id, expectedVersion, operator, reasonCode);
    return store
        .resumeDelivery(id, expectedVersion, operator, reasonCode, clock.instant())
        .orElseThrow(
            () -> new IllegalStateException("Quarantine delivery version or state changed"));
  }

  private static void validateOperatorRequest(
      UUID id, long expectedVersion, String operator, String reasonCode) {
    if (id == null
        || expectedVersion < 0
        || operator == null
        || !operator.matches("[A-Za-z0-9._@-]{1,100}")
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
