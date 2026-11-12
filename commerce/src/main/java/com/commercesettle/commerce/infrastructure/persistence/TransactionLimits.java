package com.commercesettle.commerce.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.JdbcAccessor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 잠금을 기다릴 수 있는 쓰기 트랜잭션의 DB 대기 상한. 초과하면 해당 트랜잭션을 롤백한다. */
public final class TransactionLimits {
  private TransactionLimits() {}

  public static void apply(JdbcOperations jdbc) {
    // 같은 물리 트랜잭션에서는 한 번만 설정한다. 표시는 그 트랜잭션의 연결 holder에 묶이므로
    // REQUIRES_NEW 같은 새 물리 트랜잭션은 다시 설정한다. holder를 찾지 못하면 매번 설정한다.
    Object connection = currentConnection(jdbc);
    if (connection != null && TransactionSynchronizationManager.hasResource(connection)) return;
    // 현재 트랜잭션에만 적용하며 호출자가 정한 더 짧은 상한은 늘리지 않는다.
    jdbc.queryForList(
        """
        SELECT set_config(budget.name,
            least(coalesce(nullif(settings.setting::integer,0),budget.timeout_ms),
                budget.timeout_ms)::text,true)
        FROM (VALUES ('lock_timeout',1000),('statement_timeout',5000))
            AS budget(name,timeout_ms)
        JOIN pg_settings settings ON settings.name=budget.name
        """);
    if (connection != null) mark(connection);
  }

  private static Object currentConnection(JdbcOperations jdbc) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || !(jdbc instanceof JdbcAccessor accessor)) return null;
    DataSource source = accessor.getDataSource();
    return source == null ? null : TransactionSynchronizationManager.getResource(source);
  }

  private static void mark(Object connection) {
    TransactionSynchronizationManager.bindResource(connection, Boolean.TRUE);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(connection);
          }
        });
  }
}
