package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.port.RetentionStore;
import com.commercesettle.commerce.application.port.RetentionStore.Target;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetentionService {
  private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);
  private final RetentionStore store;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public RetentionService(RetentionStore store, Clock clock, PlatformTransactionManager manager) {
    this.store = store;
    this.clock = clock;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setTimeout(10);
  }

  /** 한 대상의 실패는 그 대상만 롤백하며 나머지 묶음을 계속한다. */
  public int run(int batchSize, int maxBatches) {
    if (batchSize < 1 || batchSize > 1000 || maxBatches < 1 || maxBatches > 100)
      throw new IllegalArgumentException("Invalid retention limits");
    int processed = 0;
    for (Target target : Target.values()) {
      Set<UUID> skipped = new HashSet<>();
      for (int batch = 0; batch < maxBatches; batch++) {
        if (Thread.currentThread().isInterrupted()) return processed;
        var now = clock.instant();
        var ids = transactions.execute(status -> store.due(target, now, batchSize, skipped));
        if (ids.isEmpty()) break;
        for (UUID id : ids) {
          if (Thread.currentThread().isInterrupted()) return processed;
          try {
            var result = transactions.execute(status -> store.process(target, id, clock.instant()));
            if (result != null) processed++;
            else skipped.add(id);
          } catch (RuntimeException error) {
            skipped.add(id);
            try {
              transactions.executeWithoutResult(
                  status -> store.failed(target, id, clock.instant()));
            } catch (RuntimeException historyError) {
              LOG.atError()
                  .addKeyValue("event", "retention.history-failed")
                  .addKeyValue("targetId", id)
                  .addKeyValue("targetKind", target.name())
                  .log("Retention failure history could not be stored; target remains retryable");
            }
            LOG.atWarn()
                .addKeyValue("event", "retention.failed")
                .addKeyValue("targetId", id)
                .addKeyValue("targetKind", target.name())
                .addKeyValue("errorType", error.getClass().getName())
                .log("Retention target rolled back; other targets continue");
          }
        }
        if (ids.size() < batchSize) break;
      }
    }
    return processed;
  }

  /** 인증된 내부 운영 호출 경계에서만 사용한다. null은 보류 해제다. */
  public void hold(UUID orderId, String reason) {
    if (reason != null && (reason.isBlank() || reason.length() > 200))
      throw new IllegalArgumentException("Invalid retention hold reason");
    transactions.executeWithoutResult(status -> store.hold(orderId, reason, clock.instant()));
  }
}
