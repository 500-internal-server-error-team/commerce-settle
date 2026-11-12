package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 기존 DB 업그레이드가 초기 스키마와 같은 인덱스를 만들고, 다른 정의의 동명 인덱스면 중단하는지 확인한다. */
@Testcontainers
@Timeout(60)
class SqlLoadUpgradeIntegrationTest {
  private static final List<String> INDEXES = List.of("ix_outbox_reconciliation");
  private static final String DEFINITIONS =
      "SELECT string_agg(pg_get_indexdef(to_regclass('payment.'||name)), E'\\n' ORDER BY name)"
          + " FROM unnest(?::text[]) AS name";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Test
  @DisplayName("업그레이드를 두 번 적용해도 초기 스키마와 같은 인덱스를 만든다")
  void createsSameIndexesAsSchemaAndIsIdempotent() throws Exception {
    JdbcTemplate jdbc = jdbc();
    String schema = jdbc.queryForObject(DEFINITIONS, String.class, (Object) names());
    INDEXES.forEach(name -> jdbc.execute("DROP INDEX payment." + name));

    upgrade(jdbc);
    upgrade(jdbc);

    assertThat(jdbc.queryForObject(DEFINITIONS, String.class, (Object) names())).isEqualTo(schema);
  }

  @Test
  @DisplayName("같은 이름의 인덱스 정의가 다르면 업그레이드를 롤백한다")
  void rejectsIncompatibleExistingIndex() {
    JdbcTemplate jdbc = jdbc();
    String name = INDEXES.getFirst();
    jdbc.execute("DROP INDEX payment." + name);
    jdbc.execute("CREATE INDEX " + name + " ON payment.outbox (id)");
    try {
      assertThatThrownBy(() -> upgrade(jdbc))
          .rootCause()
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("Incompatible index");
    } finally {
      jdbc.execute("DROP INDEX payment." + name);
      upgrade(jdbc);
    }
  }

  private static String[] names() {
    return INDEXES.toArray(String[]::new);
  }

  private static JdbcTemplate jdbc() {
    DataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    return new JdbcTemplate(source);
  }

  private static void upgrade(JdbcTemplate jdbc) {
    try {
      String sql = Files.readString(Path.of("../infra/postgres/upgrade/payment-sql-load.sql"));
      jdbc.execute(sql);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
