package com.commercesettle.commerce.infrastructure.persistence.outbox;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class OutboxTransactionLimits {
  private OutboxTransactionLimits() {}

  static void apply(NamedParameterJdbcTemplate jdbc) {
    // 현재 트랜잭션에만 적용하며 호출자가 정한 더 짧은 상한은 늘리지 않는다.
    jdbc.getJdbcTemplate()
        .queryForList(
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
