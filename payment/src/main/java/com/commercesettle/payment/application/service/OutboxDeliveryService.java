package com.commercesettle.payment.application.service;

import com.commercesettle.payment.application.port.OutboxStore;
import com.commercesettle.payment.application.port.OutboxStore.Delivery;
import com.commercesettle.payment.application.port.OutboxStore.Published;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDeliveryService {
  private final OutboxStore store;

  public OutboxDeliveryService(OutboxStore store) {
    this.store = store;
  }

  @Transactional
  public List<Delivery> claim(int limit, long leaseMs, int maxAttempts) {
    if (limit < 1 || limit > 100 || leaseMs <= 0 || maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("Invalid outbox claim limits");
    }
    return store.claim(limit, leaseMs, maxAttempts);
  }

  /** 시도 상한을 넘긴 행 정리는 선점마다가 아니라 주기 복구에서만 실행한다. */
  @Transactional
  public int reviewExhausted(int limit, int maxAttempts) {
    if (limit < 1 || limit > 100 || maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("Invalid outbox review limits");
    }
    return store.reviewExhausted(limit, maxAttempts);
  }

  /** 여러 발행 결과를 한 트랜잭션에 저장한다. 결제 1건이 이벤트마다 쓰기 커밋을 따로 만들지 않게 한다(#250). */
  @Transactional
  public Set<UUID> markPublished(List<Published> results) {
    if (results.isEmpty() || results.size() > 100) {
      throw new IllegalArgumentException("Invalid outbox publication batch size");
    }
    for (Published result : results) {
      var publication = result.publication();
      if (!result.delivery().topic().equals(publication.topic())
          || publication.partition() < 0
          || publication.offset() < 0) {
        throw new IllegalArgumentException("Invalid outbox publication position");
      }
    }
    return store.markPublished(results);
  }

  @Transactional
  public boolean markFailed(Delivery delivery, int maxAttempts) {
    return store.markFailed(delivery, maxAttempts);
  }
}
