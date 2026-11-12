package com.commercesettle.payment.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 현재 트랜잭션의 대기 상한을 건다. 다른 트랜잭션의 잠금은 1초, SQL 한 문장은 5초까지만 기다리고, 호출자가 정한 더 짧은 상한은 유지한다. 상한은 트랜잭션이 끝나면
 * 풀린다.
 *
 * <p>같은 물리 트랜잭션에서는 한 번만 실행한다. 표식은 트랜잭션이 바인딩한 커넥션 홀더 단위라서 REQUIRES_NEW처럼 새 커넥션을 쓰는 트랜잭션에는 다시 적용하고,
 * 트랜잭션 종료 때 지운다. 동기화가 없는 호출은 매번 적용한다.
 */
public final class TransactionLimits {
  static final String SQL =
      """
      SELECT set_config(budget.name,
          least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),
              budget.timeout_ms)::text,true)
      FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000))
          AS budget(name,timeout_ms)
      JOIN pg_settings settings ON settings.name=budget.name
      """;

  private TransactionLimits() {}

  public static void apply(JdbcTemplate jdbc) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || jdbc.getDataSource() == null) {
      jdbc.queryForList(SQL);
      return;
    }
    Object holder = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
    if (holder != null && TransactionSynchronizationManager.hasResource(new Applied(holder))) {
      return;
    }
    jdbc.queryForList(SQL);
    holder =
        holder != null
            ? holder
            : TransactionSynchronizationManager.getResource(jdbc.getDataSource());
    if (holder == null) {
      return;
    }
    Applied marker = new Applied(holder);
    TransactionSynchronizationManager.bindResource(marker, Boolean.TRUE);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(marker);
          }
        });
  }

  /** 커넥션 홀더의 동일성으로 물리 트랜잭션을 구분한다. 홀더는 equals를 재정의하지 않는다. */
  private record Applied(Object holder) {}
}
