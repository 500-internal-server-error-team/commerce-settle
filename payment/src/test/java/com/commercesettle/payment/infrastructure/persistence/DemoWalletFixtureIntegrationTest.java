package com.commercesettle.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercesettle.payment.infrastructure.config.DemoWalletConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실제 커밋으로 멱등성을 확인하므로 테스트 트랜잭션 자동 롤백을 끄고 테스트마다 지갑을 지운다.
@DataJpaTest(properties = "app.dev-fixtures.demo-wallets=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  DemoWalletFixture.class,
  JpaMileageWalletRepository.class,
  JpaMileageEntryRepository.class
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DemoWalletFixtureIntegrationTest {
  private static final UUID BUYER_A = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID BUYER_B = UUID.fromString("00000000-0000-4000-8000-000000000011");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.3").withInitScript("db/payment.sql");

  @Autowired DemoWalletFixture fixture;
  @Autowired DataSource dataSource;

  @AfterEach
  void clean() {
    jdbc().update("DELETE FROM payment.mileage_entries");
    jdbc().update("DELETE FROM payment.mileage_wallets");
  }

  @Test
  @DisplayName("구매자 A는 100,000점과 SEED 원장 한 줄, 구매자 B는 원장 없는 0점 지갑으로 연다")
  void opensDemoWalletsWithSeedEntry() {
    fixture.load();

    assertThat(wallet(BUYER_A))
        .containsEntry("balance", 100_000L)
        .containsEntry("reserved_amount", 0L);
    assertThat(wallet(BUYER_B)).containsEntry("balance", 0L).containsEntry("reserved_amount", 0L);
    assertThat(entries(BUYER_A))
        .singleElement()
        .satisfies(
            entry ->
                assertThat(entry)
                    .containsEntry("kind", "SEED")
                    .containsEntry("amount", 100_000L)
                    .containsEntry("balance_after", 100_000L));
    assertThat(entries(BUYER_B)).isEmpty();
  }

  @Test
  @DisplayName("동시·반복 실행해도 지갑과 원장을 한 번만 만든다")
  void isIdempotentUnderConcurrentStarts() throws Exception {
    fixture.load();
    var wallets = jdbc().queryForList("SELECT * FROM payment.mileage_wallets ORDER BY id");
    var entries = jdbc().queryForList("SELECT * FROM payment.mileage_entries ORDER BY id");

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(fixture::load);
      var second = executor.submit(fixture::load);
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    fixture.load();

    assertThat(jdbc().queryForList("SELECT * FROM payment.mileage_wallets ORDER BY id"))
        .isEqualTo(wallets);
    assertThat(jdbc().queryForList("SELECT * FROM payment.mileage_entries ORDER BY id"))
        .isEqualTo(entries);
  }

  @Test
  @DisplayName("사용 중인 기존 지갑의 잔액·예약·원장은 바꾸지 않고 없는 지갑만 만든다")
  void neverOverwritesExistingWallet() {
    UUID existing = UUID.randomUUID();
    jdbc()
        .update(
            "INSERT INTO payment.mileage_wallets"
                + " (id,buyer_id,balance,reserved_amount,created_at,updated_at,version)"
                + " VALUES (?,?,3000,1000,now(),now(),7)",
            existing,
            BUYER_A);
    var before = wallet(BUYER_A);

    fixture.load();

    assertThat(wallet(BUYER_A)).isEqualTo(before);
    assertThat(entries(BUYER_A)).isEmpty();
    assertThat(wallet(BUYER_B)).containsEntry("balance", 0L);
  }

  @Test
  @DisplayName("기본값은 꺼져 있고 Worker 역할은 지갑을 만들지 않는다")
  void runsOnlyWhenEnabledOnApiRole() {
    var context =
        new ApplicationContextRunner()
            .withUserConfiguration(DemoWalletConfiguration.class)
            .withBean(DemoWalletFixture.class, () -> Mockito.mock(DemoWalletFixture.class));
    context.run(c -> assertThat(c).doesNotHaveBean("paymentDemoWallets"));
    context
        .withPropertyValues("app.dev-fixtures.demo-wallets=true", "PAYMENT_ROLE=worker")
        .run(c -> assertThat(c).doesNotHaveBean("paymentDemoWallets"));
    context
        .withPropertyValues("app.dev-fixtures.demo-wallets=true", "PAYMENT_ROLE=api")
        .run(c -> assertThat(c).hasBean("paymentDemoWallets"));
  }

  private Map<String, Object> wallet(UUID buyer) {
    return jdbc()
        .queryForMap(
            "SELECT id,balance,reserved_amount,version,updated_at FROM payment.mileage_wallets"
                + " WHERE buyer_id=?",
            buyer);
  }

  private List<Map<String, Object>> entries(UUID buyer) {
    return jdbc()
        .queryForList(
            "SELECT e.kind,e.amount,e.balance_after FROM payment.mileage_entries e"
                + " JOIN payment.mileage_wallets w ON w.id=e.wallet_id WHERE w.buyer_id=?",
            buyer);
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }
}
