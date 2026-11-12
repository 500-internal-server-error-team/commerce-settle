package com.commercesettle.financemock.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcOperations;

/** 잠금을 기다릴 수 있는 쓰기 트랜잭션의 DB 대기 상한. 초과하면 해당 트랜잭션을 롤백한다. */
final class TransactionLimits {
  private TransactionLimits() {}

  static void apply(JdbcOperations jdbc) {
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
  }
}
