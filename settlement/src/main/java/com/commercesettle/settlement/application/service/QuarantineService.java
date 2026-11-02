package com.commercesettle.settlement.application.service;

import com.commercesettle.settlement.application.port.QuarantineStore;
import com.commercesettle.settlement.application.port.QuarantineStore.Delivery;
import com.commercesettle.settlement.application.port.QuarantineStore.Source;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원문 격리와 DLT 발행 작업만 원자 저장한다. Kafka 응답은 트랜잭션 밖에서 기다린다. 운영 재처리는 아직 없다. */
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
