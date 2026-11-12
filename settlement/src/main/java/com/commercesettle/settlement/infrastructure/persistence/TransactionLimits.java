package com.commercesettle.settlement.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Inbox·Outbox·격리 저장소의 잠금 대기 1초·문장 5초 상한. 이미 더 짧은 상한은 올리지 않는다. 같은 물리 트랜잭션에서는 처음 한 번만 실행한다. 표시는 트랜잭션
 * 동기화에 두므로 REQUIRES_NEW로 시작한 트랜잭션은 다시 건다. 동기화가 없으면 매번 실행한다.
 */
final class TransactionLimits {
  private static final String SQL =
      """
      SELECT set_config(budget.name,
          least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),
              budget.timeout_ms)::text,true)
      FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000))
          AS budget(name,timeout_ms)
      JOIN pg_settings settings ON settings.name=budget.name
      """;

  private TransactionLimits() {}

  static void apply(JdbcOperations jdbc) {
    boolean tracked =
        TransactionSynchronizationManager.isSynchronizationActive()
            && TransactionSynchronizationManager.isActualTransactionActive();
    if (tracked
        && TransactionSynchronizationManager.getSynchronizations().stream()
            .anyMatch(Applied.class::isInstance)) {
      return;
    }
    jdbc.queryForList(SQL);
    if (tracked) {
      TransactionSynchronizationManager.registerSynchronization(new Applied());
    }
  }

  /** 이 트랜잭션에 상한을 건 표시. 트랜잭션이 끝나면 동기화와 함께 사라진다. */
  private static final class Applied implements TransactionSynchronization {}
}
